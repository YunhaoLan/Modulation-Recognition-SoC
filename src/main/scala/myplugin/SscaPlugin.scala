package myplugin

import spinal.core._
import vexriscv.plugin.Plugin
import vexriscv.{Stageable, DecoderService, VexRiscv}


class SscaPlugin extends Plugin[VexRiscv] {
  object IS_SSCA_TRIGGER extends Stageable(Bool)

  override def setup(pipeline: VexRiscv): Unit = {
    import pipeline.config._
    val decoderService = pipeline.service(classOf[DecoderService])

    // Set default flags to false
    decoderService.addDefault(IS_SSCA_TRIGGER, False)

    decoderService.add(
      key = M"1010101----------111-----0110011", // Accelerator trigger instruction pattern
      List(
        IS_SSCA_TRIGGER        -> True,
        REGFILE_WRITE_VALID    -> True,
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
      when(execute.input(IS_SSCA_TRIGGER)) {
        execute.output(REGFILE_WRITE_DATA) := 1
      }
    }
  }
}
