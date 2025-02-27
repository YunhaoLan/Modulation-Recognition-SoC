package mysoc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class SimpleAxiSlaveConfig(addressWidth: Int, dataWidth: Int, idWidth: Int)
case class SimpleAxiSlave(config: SimpleAxiSlaveConfig) extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(Axi4Config(
      addressWidth = config.addressWidth,
      dataWidth = config.dataWidth,
      idWidth = config.idWidth
    )))
    val regOut = out UInt(config.dataWidth bits)
  }

  // A simple register for illustration:
  val reg = Reg(UInt(config.dataWidth bits)) init(0)

  // // Minimal AXI-Lite handshake (no burst support, etc.)
  io.axi.aw.ready := True
  io.axi.w.ready  := True
  io.axi.ar.ready := True

  // Write response channel
  io.axi.b.valid          := io.axi.aw.valid && io.axi.w.valid
  io.axi.b.payload.resp   := 0
  io.axi.b.payload.id     := io.axi.aw.payload.id  // Assign the ID from the write address channel

  // Read response channel
  io.axi.r.valid          := io.axi.ar.valid
  io.axi.r.payload.data   := reg.asBits
  io.axi.r.payload.resp   := 0
  io.axi.r.payload.id     := io.axi.ar.payload.id  // Assign the ID from the read address channel
  io.axi.r.payload.last   := True                // Single beat read, so last is always True

  reg := reg // no latch
  // Write operation:
  when(io.axi.aw.valid && io.axi.w.valid) {
    reg.asBits := io.axi.w.payload.data
  }

  io.regOut := reg
}