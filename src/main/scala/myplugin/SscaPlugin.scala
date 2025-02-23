package myplugin

import spinal.core._
import vexriscv.plugin.Plugin
import vexriscv.{Stageable, DecoderService, VexRiscv}

/**
 * SscaPlugin implements two custom instructions:
 *  - A CSR-read instruction that returns the accelerator status.
 *  - A trigger instruction that sets the accelerator status to 1.
 *
 * The accelerator status register (sscaCsr) is reused for both:
 *  - Initially, sscaCsr is 0 (idle).
 *  - The trigger instruction sets sscaCsr to 1 (start).
 *  - When the accelerator finishes, its logic should write 0 to sscaCsr.
 */
class SscaPlugin extends Plugin[VexRiscv] {
  // Define stageable signals to identify the custom instructions
  object IS_SSCA_READ extends Stageable(Bool)
  object IS_SSCA_TRIGGER extends Stageable(Bool)

  // Define the CSR register for the accelerator status (1 bit: 0 = idle, 1 = busy)
  var sscaCsr = Reg(UInt(1 bits)) init(0)

  override def setup(pipeline: VexRiscv): Unit = {
    import pipeline.config._
    val decoderService = pipeline.service(classOf[DecoderService])

    // Set default flags to false
    decoderService.addDefault(IS_SSCA_READ, False)
    decoderService.addDefault(IS_SSCA_TRIGGER, False)

    // Register a custom instruction to read the SSCA status.
    // When this instruction is executed, the register file will be written
    // with the value of sscaCsr.
    // Note: The bit pattern used here ("0000100----------010-----0110011")
    // is just an example. Make sure to choose an encoding that does not conflict
    // with any standard or other custom instructions.
    decoderService.add(
      key = M"0000100----------010-----0110011", // CSR read instruction pattern, the ----- after 010 are the dst register field!!!
      List(
        IS_SSCA_READ           -> True,
        REGFILE_WRITE_VALID    -> True,  // The instruction writes a value back to a register
        BYPASSABLE_EXECUTE_STAGE -> True,
        RS1_USE                -> False, // no source reg
        RS2_USE                -> False
      )
    )

    // Register a custom instruction to trigger the SSCA accelerator.
    // When this instruction is executed, sscaCsr is set to 1.
    // This instruction does not write a value to the register file.
    decoderService.add(
      key = M"0000100----------011-----0110011", // Accelerator trigger instruction pattern
      List(
        IS_SSCA_TRIGGER        -> True,
        REGFILE_WRITE_VALID    -> False, // No register file write; side effect only
        RS1_USE                -> False,
        RS2_USE                -> False
      )
    )
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    // Insert logic in the execute stage
    execute plug new Area {
      // When the CSR read instruction is active, output the status into the register file.
      when(execute.input(IS_SSCA_READ)) {
        execute.output(REGFILE_WRITE_DATA) := sscaCsr.asBits
      }

      // When the trigger instruction is active, set the status to 1 (triggering the accelerator)
      when(execute.input(IS_SSCA_TRIGGER)) {
        sscaCsr := 1
      }
    }
  }
}
