package mysoc



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



// import spinal.core._
// import spinal.lib._
// import spinal.lib.bus.amba4.axi._
import vexriscv.demo.{Briey, BrieyConfig}
import myplugin.SscaPlugin
import myacc.SscaAccelerator
import mysoc.SimpleAxiSlave
import mysoc.SimpleAxiSlaveConfig
// import spinal.lib.com.jtag.Jtag
// import spinal.lib.memory.sdram.SdramGeneration.SDR
// import spinal.lib.memory.sdram._
// import spinal.lib.memory.sdram.sdr.sim.SdramModel
// import spinal.lib.memory.sdram.sdr.{Axi4SharedSdramCtrl, IS42x320D, SdramInterface, SdramTimings}
import spinal.lib.bus.misc.SizeMapping

class MyBrieyWrapper extends Component {
  val io = new Bundle {
    // Briey I/Os:
    val asyncReset = in Bool()
    val axiClk     = in Bool()
    val vgaClk     = in Bool()
    val jtag       = slave(Jtag())
    val sdram      = master(SdramInterface(BrieyConfig.default.sdramLayout))

    // Additional signals:
    val sscaStatusOut = out Bool()
    // External AXI master interface for testing:
    val extAxiMaster  = master(Axi4(Axi4Config(addressWidth = 4, dataWidth = 32, idWidth = 4)))
  }

  // Instantiate the original Briey SoC.
  // Here we assume Briey is configured with all of its peripherals.
  val briey = new Briey(BrieyConfig.default)
  // Connect the basic signals
  briey.io.asyncReset := io.asyncReset
  briey.io.axiClk     := io.axiClk
  briey.io.vgaClk     := io.vgaClk
  briey.io.jtag       <> io.jtag
  briey.io.sdram      <> io.sdram

  // Accelerator integration:
  val sscaPlugin = new SscaPlugin
  // Ideally, you would have added sscaPlugin into the Briey CPU configuration.
  // For this wrapper, we assume we can integrate it externally.
  val sscaAccel = SscaAccelerator()
  sscaPlugin.sscaCsr := (!sscaAccel.io.done).asUInt
  io.sscaStatusOut  := sscaPlugin.sscaCsr.asBool

  // Now, to add your custom AXI slave and external master,
  // you need access to the internal AXI crossbar.
  // If Briey doesn't expose its crossbar publicly, you might need to subclass or modify Briey.
  // For this example, assume that we have a way to get the crossbar, for example:
  val axiCrossbar = briey.axiCrossbar // <-- This method must be implemented in Briey

  // Instantiate your custom AXI slave peripheral.
  val myAxiSlave = new SimpleAxiSlave(SimpleAxiSlaveConfig(addressWidth = 4, dataWidth = 32, idWidth = 4))
  // Add the AXI slave to the crossbar with a given base address.
  axiCrossbar.addSlaves(
    myAxiSlave.io.axi -> SizeMapping(0xF0100000L, 0x1000L)
  )

  // Add the external AXI master port to the crossbar.
  axiCrossbar.addConnections(
  io.extAxiMaster -> List(myAxiSlave.io.axi)
  )

}

object MyBrieyWrapper {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new MyBrieyWrapper)
  }
}
