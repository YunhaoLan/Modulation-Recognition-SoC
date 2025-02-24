package mysoc

import spinal.core._
import vexriscv.demo.Briey
import vexriscv.demo.BrieyConfig
import myplugin.SscaPlugin
import myacc.SscaAccelerator
import mysoc.SimpleAxiSlave
import mysoc.SimpleAxiSlaveConfig


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

    // External AXI master interface for testing
    val extAxiMaster  = master(Axi4Lite(Axi4LiteConfig(addressWidth = 4, dataWidth = 32)))
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

  // --- Integrate an internal AXI slave ---
  // Instantiate our AXI slave peripheral.
  val myAxiSlave = new SimpleAxiSlave(SimpleAxiSlaveConfig(addressWidth = 4, dataWidth = 32))

  // Add the AXI slave to the internal crossbar.
  // NOTE: For this to work, the Briey design must expose its axiCrossbar instance.
  // Here we assume that 'briey' has a public member 'axiCrossbar' of type Axi4CrossbarFactory.
  // You might need to modify the Briey design or wrap it appropriately if it doesn't expose this.
  briey.axiCrossbar.addSlaves(
    myAxiSlave.io.axi -> (0xF0100000L, 0x1000) // Base address and size for the new slave.
  )

  // --- External AXI Master Integration ---
  // Add the external AXI master (extAxiMaster) to the crossbar so that it can access the internal slave.
  // This means that an external testbench or controller driving extAxiMaster
  // will see the memory-mapped registers of myAxiSlave.
  briey.axiCrossbar.addMasters(
    io.extAxiMaster -> List(myAxiSlave.io.axi)
  )
}

object MyBrieyTop {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new MyBrieyTop)
  }
}
