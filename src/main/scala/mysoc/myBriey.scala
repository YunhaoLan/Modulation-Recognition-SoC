package mysoc

import spinal.core._
import vexriscv.demo.Briey
import vexriscv.demo.BrieyConfig
import myplugin.SscaPlugin
import myacc.SscaAccelerator

class MyBrieyTop extends Component {
  val io = new Bundle {
    // Expose existing Briey I/Os as needed...
    val asyncReset = in Bool()
    val axiClk     = in Bool()
    val vgaClk     = in Bool()
    val jtag       = slave(BrieyConfig.default.jtag) // or explicitly declare if needed
    // And additional signals:
    val sscaStatusOut = out Bool()
    // ... add more as required.
  }

  // Instantiate Briey with a modified configuration that includes our custom plugin.
  val brieyConfig = BrieyConfig.default.copy(
    // Adjust configuration parameters if necessary.
    // For example, you might want to add your custom plugin:
    cpuPlugins = BrieyConfig.default.cpuPlugins.clone()
  )
  // Add our accelerator plugin.
  val sscaPlugin = new SscaPlugin
  brieyConfig.cpuPlugins += sscaPlugin

  // Instantiate the base Briey SoC.
  val briey = new Briey(brieyConfig)
  // Connect the reset and clock signals
  briey.io.asyncReset := io.asyncReset
  briey.io.axiClk     := io.axiClk
  briey.io.vgaClk     := io.vgaClk
  // (Other IO connections would follow as needed)

  // Instantiate our accelerator (using a BlackBox wrapper)
  val sscaAccel = SscaAccelerator()

  // Connect the accelerator's "done" signal (inverted) to our custom plugin.
  sscaPlugin.sscaCsr := !sscaAccel.io.done

  // Optionally, expose the accelerator status externally.
  io.sscaStatusOut := sscaPlugin.sscaCsr

  // Optionally, connect the accelerator to an appropriate bus from Briey if required.
  // (For example, if your accelerator needs to access memory-mapped registers,
  // you could route it through the existing APB bridge in Briey.)
}

object MyBrieyTop {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new MyBrieyTop)
  }
}
