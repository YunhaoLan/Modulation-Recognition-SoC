package vexriscv.demo

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class SpiControllerConfig(
    dataWidth: Int = 8,
    timerWidth: Int = 16,
    ssWidth: Int = 1,
    fifoDepth: Int = 8
)

class Axi4LiteSpi(config: SpiControllerConfig) extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(Axi4Config(addressWidth = 12, dataWidth = 32)))
    val spi = master(SpiMaster(ssWidth = config.ssWidth))
    val interrupt = out Bool()
  }

  val spi = new Bundle {
    val sclk = out Bool()
    val mosi = out Bool()
    val miso = in Bool()
    val ss = out Bits(config.ssWidth bits)
  }

  // Register Definitions
  val regs = new Area {
    // Control Register
    val CTRL = Reg(Bits(32 bits)) init(0)
    val enable = CTRL(0)
    val cpol = CTRL(1)
    val cpha = CTRL(2)
    val lsbFirst = CTRL(3)
    val irqEnable = CTRL(4)

    // Clock Divider
    val DIV = Reg(UInt(config.timerWidth bits)) init(0)

    // Data Registers
    val TXDATA = Reg(Bits(config.dataWidth bits)) init(0)
    val RXDATA = Reg(Bits(config.dataWidth bits)) init(0)

    // Status Register
    val STATUS = Reg(Bits(32 bits)) init(0)
    val txBusy = STATUS(0)
    val rxValid = STATUS(1)
    val txEmpty = STATUS(2)
    val rxFull = STATUS(3)
  }

  // FIFO Buffers
  val txFifo = StreamFifo(Bits(config.dataWidth bits), config.fifoDepth)
  val rxFifo = StreamFifo(Bits(config.dataWidth bits), config.fifoDepth)

  // SPI Engine
  val spiEngine = new Area {
    val timer = Reg(UInt(config.timerWidth bits)) init(0)
    val shift = Reg(Bool()) init(False)
    val bitCounter = Reg(UInt(log2Up(config.dataWidth) bits)) init(0)
    val txShift = Reg(Bits(config.dataWidth bits))
    val rxShift = Reg(Bits(config.dataWidth bits))
    val ssActive = Reg(Bool()) init(False)
    val sclk = Reg(Bool()) init(False)
    
    // SPI State Machine
    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        onEntry {
          ssActive := False
          sclk := regs.cpol
        }
        whenIsActive {
          when(txFifo.io.pop.valid) {
            txShift := txFifo.io.pop.payload
            bitCounter := 0
            shift := True
            ssActive := True
            timer := regs.DIV
            goto(shifting)
          }
        }
      }

      val shifting: State = new State {
        onEntry {
          sclk := !regs.cpol
          timer := regs.DIV >> 1
        }
        
        whenIsActive {
          when(timer === 0) {
            sclk := !sclk
            timer := regs.DIV
            
            when(sclk ^ regs.cpha) {
              // Shift data on appropriate clock edge
              txShift := txShift ## B"0"
              rxShift := rxShift ## spi.miso
              bitCounter := bitCounter + 1
              
              when(bitCounter === config.dataWidth - 1) {
                shift := False
                ssActive := False
                rxFifo.io.push.valid := True
                rxFifo.io.push.payload := rxShift
                goto(idle)
              }
            }
          }.otherwise {
            timer := timer - 1
          }
        }
      }
    }

    // MOSI output
    spi.mosi := txShift.msb
    // SCLK output
    spi.sclk := sclk
    // SS output
    spi.ss := ~B(ssActive ## B(0, config.ssWidth - 1 bits))
  }

  // AXI4-Lite Interface
  val axiCtrl = new Axi4SlaveFactory(io.axi)
  
  // Register Map
  axiCtrl.readAndWrite(
    regs.CTRL, 
    address = 0x00,
    bitOffset = 0
  )
  
  axiCtrl.write(
    regs.DIV, 
    address = 0x04,
    bitOffset = 0
  )
  
  axiCtrl.writeStream(
    txFifo.io.push, 
    address = 0x08,
    validBitOffset = 31,
    payloadBitOffset = 0
  )
  
  axiCtrl.readStream(
    rxFifo.io.pop, 
    address = 0x0C,
    validBitOffset = 31,
    payloadBitOffset = 0
  )
  
  axiCtrl.read(
    regs.STATUS, 
    address = 0x10,
    bitOffset = 0
  )

  // Status flags
  regs.STATUS(0) := spiEngine.fsm.isActive(spiEngine.fsm.shifting)
  regs.STATUS(1) := rxFifo.io.pop.valid
  regs.STATUS(2) := txFifo.io.push.ready
  regs.STATUS(3) := !rxFifo.io.push.ready

  // Interrupt generation
  io.interrupt := regs.irqEnable && (rxFifo.io.pop.valid || !txFifo.io.push.ready)
}

case class SpiMaster(ssWidth: Int) extends Bundle {
  val sclk = Bool()
  val mosi = Bool()
  val miso = Bool()
  val ss = Bits(ssWidth bits)
}