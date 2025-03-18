package mycpu

import vexriscv.plugin._
import spinal.core._
import vexriscv._ 

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

import myplugin._


class MyVex(val config: VexRiscvConfig) extends Component {
  val io = new Bundle {
    // Expose the plugin signal as top-level IO
    val ssca_status = out UInt(1 bits)
    // (other IO signals for the SoC)
  }

  // Instantiate the CPU core.
  // Note: This relies on VexRiscv having a public member (or IO) that gives you the plugin's value.
  val cpu = new VexRiscv(config)

  // For example, assume VexRiscv now defines:
  // val ssca_status: UInt = (internal signal from plugin)
  // We can then directly connect:
  io.ssca_status <> cpu.acc_io.ssca_status
}
