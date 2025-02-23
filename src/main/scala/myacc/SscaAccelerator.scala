package myacc

import spinal.core._

/**
 * BlackBox wrapper for the SSCA accelerator implemented in Verilog.
 * Adjust the I/O ports to match those defined in your Verilog module.
 */
class SscaAccelerator extends BlackBox {
  val io = new Bundle {
    // ssca's done output signal
    val done = out Bool()
    // Add any additional ports your accelerator requires.
    // For example:
    // val start  = in Bool()
    // val dataIn = in UInt(32 bits)
    // val dataOut = out UInt(32 bits)
  }
  // Prevent prefixing IO names so they match the Verilog module port names exactly.
  noIoPrefix()
  // This name must match the module name in your Verilog source.
  setDefinitionName("SscaAccelerator") //need to change it later (corresponding to ssca top's name)
}

object SscaAccelerator {
  def apply(): SscaAccelerator = new SscaAccelerator
}
