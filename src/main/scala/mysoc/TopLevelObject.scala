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


object TopLevelVerilog {
  def main(args: Array[String]): Unit = {
    // Generate RTL from the TopLevel design using the default configuration.
    SpinalConfig(targetDirectory = "rtl").generateVerilog(new TopLevel(TopLevelConfig.default))
  }
}

// Simulation object: here you can initialize the on‑chip RAM (e.g. from a hex file)
// and simulate basic clock/reset and (optionally) UART transactions.
// object TopLevelSim {
//   def main(args: Array[String]): Unit = {
//     SimConfig.withWave.compile(new TopLevel(TopLevelConfig.default)).doSim { dut =>
//       // Create a simple clock generator (adjust period as needed)
//       dut.io.axiClk #= false
//       fork {
//         while (true) {
//           dut.io.axiClk #= !dut.io.axiClk.toBoolean
//           sleep(5)
//         }
//       }

//       // Apply reset
//       dut.io.asyncReset #= true
//       sleep(20)
//       dut.io.asyncReset #= false

//       // (Optional) Preload on‑chip RAM with a hex file.
//       // Replace "path/to/firmware.hex" with your actual hex file path.
//       HexTools.initRam(dut.ramInst.ram, "src/main/ressource/hex/firmware.hex", 0x80000000L)

//       // Simulation run – add additional testbench stimulus if needed.
//       sleep(1000)
//       simSuccess()
//     }
//   }
// }

