package mysoc
import spinal.core._


import vexriscv.plugin._
import vexriscv._
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba4.axi._
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.jtag.sim.JtagTcp
import spinal.lib.com.uart.sim.{UartDecoder, UartEncoder}
import spinal.lib.com.uart.{Apb3UartCtrl, Uart, UartCtrlGenerics, UartCtrlMemoryMappedConfig}
import spinal.lib.graphic.RgbConfig
import spinal.lib.graphic.vga.{Axi4VgaCtrl, Axi4VgaCtrlGenerics, Vga}
import spinal.lib.io.TriStateArray
import spinal.lib.memory.sdram.SdramGeneration.SDR
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import spinal.lib.memory.sdram.sdr.{Axi4SharedSdramCtrl, IS42x320D, SdramInterface, SdramTimings}
import spinal.lib.misc.HexTools
import spinal.lib.soc.pinsec.{PinsecTimerCtrl, PinsecTimerCtrlExternal}
import spinal.lib.system.debugger.{JtagAxi4SharedDebugger, JtagBridge, SystemDebugger, SystemDebuggerConfig}

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

import myplugin._
import myacc._

class TopLevel(config: TopLevelConfig) extends Component {
  val io = new Bundle {
    // Clocks and reset
    val axiClk     = in  Bool()
    val asyncReset = in  Bool()
    // UART interface
    val uart       = master(Uart())
  }

  // Define the AXI clock domain (for CPU and AXI infrastructure)
  val axiClockDomain = ClockDomain(
    clock = io.axiClk,
    reset = RegNext(io.asyncReset)
  )
  // Use the same clock domain for debug purposes
  val debugClockDomain = ClockDomain(
    clock = io.axiClk,
    reset = RegNext(io.asyncReset)
  )

  // All AXI‐related components are instantiated in this clocking area========================================
  val axiArea = new ClockingArea(axiClockDomain) {
    // Instantiate an on‑chip RAM (to hold code/data)
    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = config.onChipRamSize,
      idWidth   = 4
    )

    // Expose the RAM instance so that simulation can initialize it
    val ramInst = ram

    // Instantiate an AXI–to–APB bridge
    val apbBridge = Axi4SharedToApb3Bridge(
      addressWidth = 20,
      dataWidth    = 32,
      idWidth      = 4
    )

    // Instantiate the single UART peripheral on APB
    val uartCtrl = Apb3UartCtrl(config.uartCtrlConfig)
    // Make it public for simulation (optional)
    uartCtrl.io.apb.addAttribute(Verilator.public)
    // The CPU core area----------------------------------------------------------------------------------------
    val core = new Area {
      // Build a VexRiscv configuration with the plugins from the config
      val sscaPlugin = new SscaPlugin()
      val my_plugins = config.cpuPlugins ++ Seq(sscaPlugin) //add sscaPlugin into plugin list
      val vexConfig = VexRiscvConfig(my_plugins)
      val cpu       = new VexRiscv(vexConfig)

      val iBus = {
        var bus: Axi4ReadOnly = null
        for(plugin <- vexConfig.plugins) {
          plugin match {
            case p: IBusSimplePlugin => bus = p.iBus.toAxi4ReadOnly()
            case p: IBusCachedPlugin => bus = p.iBus.toAxi4ReadOnly()
            case _ =>
          }
        }
        bus
      }
      val dBus = {
        var bus: Axi4Shared = null
        for(plugin <- vexConfig.plugins) {
          plugin match {
            case p: DBusSimplePlugin => bus = p.dBus.toAxi4Shared()
            case p: DBusCachedPlugin => bus = p.dBus.toAxi4Shared(true)
            case _ =>
          }
        }
        bus
      }
      for(plugin <- vexConfig.plugins) {
        plugin match {
          case csr: CsrPlugin => {
            csr.timerInterrupt    := False
            csr.externalInterrupt := False
          }
          case _ =>
        }
      }
    }//-------------------------------------------------------------------------------------------------------------

    // Build the AXI crossbar connecting the CPU buses to the slaves.
    // Two slaves are attached: the on‑chip RAM at address 0x80000000
    // and the APB bridge at 0xF0000000.
    val axiCrossbar = Axi4CrossbarFactory()
    axiCrossbar.addSlaves(
      ram.io.axi       -> (0x80000000L, config.onChipRamSize),
      apbBridge.io.axi -> (0xF0000000L, 1 MB)
    )
    axiCrossbar.addConnections(
      // Instruction bus only goes to RAM
      core.iBus -> List(ram.io.axi),
      // Data bus can access both RAM (for normal loads/stores) and the APB bridge (for the UART)
      core.dBus -> List(ram.io.axi, apbBridge.io.axi)
    )
    axiCrossbar.build()

    // Decode the APB address space: here we only have one peripheral (UART) at offset 0x00000
    Apb3Decoder(
      master = apbBridge.io.apb,
      slaves = List(
        uartCtrl.io.apb -> (0x00000, 4 kB)
      )
    )

    // Connect the UART peripheral to the top-level IO
    uartCtrl.io.uart <> io.uart
  }// =====================================================================================================================

  // Expose the RAM instance for simulation purposes (e.g. hex file initialization)
  val ramInst = axiArea.ramInst
}
