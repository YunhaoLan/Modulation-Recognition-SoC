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

import myplugin.SscaPlugin
import myacc.SscaAccelerator
import mysoc.SimpleAxiSlave
import mysoc.SimpleAxiSlaveConfig
import spinal.lib.bus.misc.SizeMapping

case class BrieyConfig(axiFrequency : HertzNumber,
                       onChipRamSize : BigInt,
                       sdramLayout: SdramLayout,
                       sdramTimings: SdramTimings,
                       cpuPlugins : ArrayBuffer[Plugin[VexRiscv]],
                       uartCtrlConfig : UartCtrlMemoryMappedConfig)

object BrieyConfig{

  def default = {
    val config = BrieyConfig(
      axiFrequency = 50 MHz,
      onChipRamSize  = 4 kB,
      sdramLayout = IS42x320D.layout,
      sdramTimings = IS42x320D.timingGrade7,
      uartCtrlConfig = UartCtrlMemoryMappedConfig(
        uartCtrlConfig = UartCtrlGenerics(
          dataWidthMax      = 8,
          clockDividerWidth = 20,
          preSamplingSize   = 1,
          samplingSize      = 5,
          postSamplingSize  = 2
        ),
        txFifoDepth = 16,
        rxFifoDepth = 16
      ),
      cpuPlugins = ArrayBuffer(
        new PcManagerSimplePlugin(0x80000000l, false),
        //          new IBusSimplePlugin(
        //            interfaceKeepData = false,
        //            catchAccessFault = true
        //          ),
        new IBusCachedPlugin(
          resetVector = 0x80000000l,
          prediction = STATIC,
          config = InstructionCacheConfig(
            cacheSize = 4096,
            bytePerLine =32,
            wayCount = 1,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = 32,
            catchIllegalAccess = true,
            catchAccessFault = true,
            asyncTagMemory = false,
            twoCycleRam = true,
            twoCycleCache = true
          )
          //            askMemoryTranslation = true,
          //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //              portTlbSize = 4
          //            )
        ),
        //                    new DBusSimplePlugin(
        //                      catchAddressMisaligned = true,
        //                      catchAccessFault = true
        //                    ),
        new DBusCachedPlugin(
          config = new DataCacheConfig(
            cacheSize         = 4096,
            bytePerLine       = 32,
            wayCount          = 1,
            addressWidth      = 32,
            cpuDataWidth      = 32,
            memDataWidth      = 32,
            catchAccessError  = true,
            catchIllegal      = true,
            catchUnaligned    = true
          ),
          memoryTranslatorPortConfig = null
          //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //              portTlbSize = 6
          //            )
        ),
        new StaticMemoryTranslatorPlugin(
          ioRange      = _(31 downto 28) === 0xF
        ),
        new DecoderSimplePlugin(
          catchIllegalInstruction = true
        ),
        new RegFilePlugin(
          regFileReadyKind = plugin.SYNC,
          zeroBoot = false
        ),
        new IntAluPlugin,
        new SrcPlugin(
          separatedAddSub = false,
          executeInsertion = true
        ),
        new FullBarrelShifterPlugin,
        new MulPlugin,
        new DivPlugin,
        new HazardSimplePlugin(
          bypassExecute           = true,
          bypassMemory            = true,
          bypassWriteBack         = true,
          bypassWriteBackBuffer   = true,
          pessimisticUseSrc       = false,
          pessimisticWriteRegFile = false,
          pessimisticAddressMatch = false
        ),
        new BranchPlugin(
          earlyBranch = false,
          catchAddressMisaligned = true
        ),
        new CsrPlugin(
          config = CsrPluginConfig(
            catchIllegalAccess = false,
            mvendorid      = null,
            marchid        = null,
            mimpid         = null,
            mhartid        = null,
            misaExtensionsInit = 66,
            misaAccess     = CsrAccess.NONE,
            mtvecAccess    = CsrAccess.NONE,
            mtvecInit      = 0x80000020l,
            mepcAccess     = CsrAccess.READ_WRITE,
            mscratchGen    = false,
            mcauseAccess   = CsrAccess.READ_ONLY,
            mbadaddrAccess = CsrAccess.READ_ONLY,
            mcycleAccess   = CsrAccess.NONE,
            minstretAccess = CsrAccess.NONE,
            ecallGen       = false,
            wfiGenAsWait         = false,
            ucycleAccess   = CsrAccess.NONE,
            uinstretAccess = CsrAccess.NONE
          )
        ),
        new YamlPlugin("cpu0.yaml")
      )
    )
    config
  }
}



class Briey(val config: BrieyConfig) extends Component{

  //Legacy constructor
  def this(axiFrequency: HertzNumber) {
    this(BrieyConfig.default.copy(axiFrequency = axiFrequency))
  }

  import config._
  val debug = true
  val interruptCount = 4
  def vgaRgbConfig = RgbConfig(5,6,5)

  val io = new Bundle{
    //Clocks / reset
    val asyncReset = in Bool()
    val axiClk     = in Bool()
    val vgaClk     = in Bool()

    //Main components IO
    val jtag       = slave(Jtag())
    val sdram      = master(SdramInterface(sdramLayout))

    //Peripherals IO
    val gpioA         = master(TriStateArray(32 bits))
    val gpioB         = master(TriStateArray(32 bits))
    val uart          = master(Uart())
    val vga           = master(Vga(vgaRgbConfig))
    val timerExternal = in(PinsecTimerCtrlExternal())
    val coreInterrupt = in Bool()

    val regOut = out Bool()
    val sscaStatusOut = out Bool()
    
    // val extAxiMaster  = master(Axi4(Axi4Config(addressWidth = 12, dataWidth = 32, idWidth = 4)))

    // Write Address (AW) channel
    val axi_aw_addr  = out Bits(12 bits)
    val axi_aw_valid = out Bool()
    val axi_aw_ready = in  Bool()

    // Write Data (W) channel
    val axi_w_data  = out Bits(32 bits)
    val axi_w_strb  = out Bits(4 bits) // usually one bit per byte lane
    val axi_w_valid = out Bool()
    val axi_w_ready = in  Bool()

    // Write Response (B) channel
    val axi_b_valid = in  Bool()
    val axi_b_resp  = in  Bits(2 bits) // response is typically 2 bits ("OKAY" = 0)
    val axi_b_ready = out Bool()

    // Read Address (AR) channel
    val axi_ar_addr  = out Bits(12 bits)
    val axi_ar_valid = out Bool()
    val axi_ar_ready = in  Bool()

    // Read Data (R) channel
    val axi_r_data  = in  Bits(32 bits)
    val axi_r_valid = in  Bool()
    val axi_r_resp  = in  Bits(2 bits)
    val axi_r_ready = out Bool()


    //tb in
    val tb_axi_aw_addr  = in Bits(12 bits)
    val tb_axi_aw_valid = in Bool()
    val tb_axi_w_data  = in Bits(32 bits)
    val tb_axi_w_strb  = in Bits(4 bits) 
    val tb_axi_w_valid = in Bool()

  }

  val extAxiMaster  = master(Axi4(Axi4Config(addressWidth = 12, dataWidth = 32, idWidth = 4)))
  //--- Manual Connection ---
  //tb
  extAxiMaster.aw.addr := tb_axi_aw_addr
  extAxiMaster.aw.valid := tb_axi_aw_valid
  extAxiMaster.w.data := tb_axi_w_data
  extAxiMaster.w.strb := tb_axi_w_strb
  extAxiMaster.w.valid := tb_axi_w_valid
  // Write Address Channel (AW)
  io.axi_aw_addr  := extAxiMaster.aw.addr.asBits
  io.axi_aw_valid := extAxiMaster.aw.valid
  extAxiMaster.aw.ready := io.axi_aw_ready

  // Write Data Channel (W)
  io.axi_w_data  := extAxiMaster.w.data
  io.axi_w_strb  := extAxiMaster.w.strb
  io.axi_w_valid := extAxiMaster.w.valid
  extAxiMaster.w.ready := io.axi_w_ready

  // Write Response Channel (B) - this channel is driven by the slave
  io.axi_b_valid := extAxiMaster.b.valid
  io.axi_b_resp  := extAxiMaster.b.payload.resp
  extAxiMaster.b.ready := io.axi_b_ready

  // Read Address Channel (AR)
  io.axi_ar_addr  := extAxiMaster.ar.addr.asBits
  io.axi_ar_valid := extAxiMaster.ar.valid
  extAxiMaster.ar.ready := io.axi_ar_ready

  // Read Data Channel (R) - this channel is driven by the slave
  io.axi_r_data  := extAxiMaster.r.payload.data
  io.axi_r_valid := extAxiMaster.r.valid
  io.axi_r_resp  := extAxiMaster.r.payload.resp
  extAxiMaster.r.ready := io.axi_r_ready
  
  val sscaPlugin = new SscaPlugin
  val sscaAccel = SscaAccelerator()
  sscaPlugin.sscaCsr := (!sscaAccel.io.done).asUInt
  io.sscaStatusOut  := sscaPlugin.sscaCsr.asBool


  val resetCtrlClockDomain = ClockDomain(
    clock = io.axiClk,
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    val systemResetUnbuffered  = False
    //    val coreResetUnbuffered = False

    //Implement an counter to keep the reset axiResetOrder high 64 cycles
    // Also this counter will automaticly do a reset when the system boot.
    val systemResetCounter = Reg(UInt(6 bits)) init(0)
    when(systemResetCounter =/= U(systemResetCounter.range -> true)){
      systemResetCounter := systemResetCounter + 1
      systemResetUnbuffered := True
    }
    when(BufferCC(io.asyncReset)){
      systemResetCounter := 0
    }

    //Create all reset used later in the design
    val systemReset  = RegNext(systemResetUnbuffered)
    val axiReset     = RegNext(systemResetUnbuffered)
    val vgaReset     = BufferCC(axiReset)
  }

  val axiClockDomain = ClockDomain(
    clock = io.axiClk,
    reset = resetCtrl.axiReset,
    frequency = FixedFrequency(axiFrequency) //The frequency information is used by the SDRAM controller
  )

  val debugClockDomain = ClockDomain(
    clock = io.axiClk,
    reset = resetCtrl.systemReset,
    frequency = FixedFrequency(axiFrequency)
  )

  val vgaClockDomain = ClockDomain(
    clock = io.vgaClk,
    reset = resetCtrl.vgaReset
  )

  val axi = new ClockingArea(axiClockDomain) {
    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = onChipRamSize,
      idWidth = 4
    )

    val sdramCtrl = Axi4SharedSdramCtrl(
      axiDataWidth = 32,
      axiIdWidth   = 4,
      layout       = sdramLayout,
      timing       = sdramTimings,
      CAS          = 3
    )


    val apbBridge = Axi4SharedToApb3Bridge(
      addressWidth = 20,
      dataWidth    = 32,
      idWidth      = 4
    )

    val gpioACtrl = Apb3Gpio(
      gpioWidth = 32,
      withReadSync = true
    )
    val gpioBCtrl = Apb3Gpio(
      gpioWidth = 32,
      withReadSync = true
    )
    val timerCtrl = PinsecTimerCtrl()


    val uartCtrl = Apb3UartCtrl(uartCtrlConfig)
    uartCtrl.io.apb.addAttribute(Verilator.public)


    val vgaCtrlConfig = Axi4VgaCtrlGenerics(
      axiAddressWidth = 32,
      axiDataWidth    = 32,
      burstLength     = 8,
      frameSizeMax    = 2048*1512*2,
      fifoSize        = 512,
      rgbConfig       = vgaRgbConfig,
      vgaClock        = vgaClockDomain
    )
    val vgaCtrl = Axi4VgaCtrl(vgaCtrlConfig)



    val core = new Area{
      val config = VexRiscvConfig(
        plugins = cpuPlugins += new DebugPlugin(debugClockDomain)
      )

      val cpu = new VexRiscv(config)
      var iBus : Axi4ReadOnly = null
      var dBus : Axi4Shared = null
      for(plugin <- config.plugins) plugin match{
        case plugin : IBusSimplePlugin => iBus = plugin.iBus.toAxi4ReadOnly()
        case plugin : IBusCachedPlugin => iBus = plugin.iBus.toAxi4ReadOnly()
        case plugin : DBusSimplePlugin => dBus = plugin.dBus.toAxi4Shared()
        case plugin : DBusCachedPlugin => dBus = plugin.dBus.toAxi4Shared(true)
        case plugin : CsrPlugin        => {
          plugin.externalInterrupt := BufferCC(io.coreInterrupt)
          plugin.timerInterrupt := timerCtrl.io.interrupt
        }
        case plugin : DebugPlugin      => debugClockDomain{
          resetCtrl.axiReset setWhen(RegNext(plugin.io.resetOut))
          io.jtag <> plugin.io.bus.fromJtag()
        }
        case _ =>
      }
    }


    val axiCrossbar = Axi4CrossbarFactory()
    val myAxiSlave = new SimpleAxiSlave(SimpleAxiSlaveConfig(addressWidth = 12, dataWidth = 32, idWidth = 4)) /////////////////////////

    axiCrossbar.addSlaves(
      ram.io.axi       -> (0x80000000L,   onChipRamSize),
      sdramCtrl.io.axi -> (0x40000000L,   sdramLayout.capacity),
      apbBridge.io.axi -> (0xF0000000L,   1 MB),
      myAxiSlave.io.axi -> SizeMapping(0xF0100000L, 0x1000L) //////////////////////////////////////////////////////////////////////////////
    )

    axiCrossbar.addConnections(
      core.iBus       -> List(ram.io.axi, sdramCtrl.io.axi),
      core.dBus       -> List(ram.io.axi, sdramCtrl.io.axi, apbBridge.io.axi),
      vgaCtrl.io.axi  -> List(            sdramCtrl.io.axi),
      extAxiMaster -> List(myAxiSlave.io.axi) //////////////////////////////////////////////////////////////////////////////
    )


    axiCrossbar.addPipelining(apbBridge.io.axi)((crossbar,bridge) => {
      crossbar.sharedCmd.halfPipe() >> bridge.sharedCmd
      crossbar.writeData.halfPipe() >> bridge.writeData
      crossbar.writeRsp             << bridge.writeRsp
      crossbar.readRsp              << bridge.readRsp
    })

    axiCrossbar.addPipelining(sdramCtrl.io.axi)((crossbar,ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    axiCrossbar.addPipelining(ram.io.axi)((crossbar,ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    axiCrossbar.addPipelining(vgaCtrl.io.axi)((ctrl,crossbar) => {
      ctrl.readCmd.halfPipe()    >>  crossbar.readCmd
      ctrl.readRsp               <<  crossbar.readRsp
    })

    axiCrossbar.addPipelining(core.dBus)((cpu,crossbar) => {
      cpu.sharedCmd             >>  crossbar.sharedCmd
      cpu.writeData             >>  crossbar.writeData
      cpu.writeRsp              <<  crossbar.writeRsp
      cpu.readRsp               <-< crossbar.readRsp //Data cache directly use read responses without buffering, so pipeline it for FMax
    })

    // val myAxiSlave = new SimpleAxiSlave(SimpleAxiSlaveConfig(addressWidth = 12, dataWidth = 32, idWidth = 4))
    // axiCrossbar.addSlaves(
    //     myAxiSlave.io.axi -> SizeMapping(0xF0100000L, 0x1000L)
    // )

    // axiCrossbar.addConnections(
    //     io.extAxiMaster -> List(myAxiSlave.io.axi)
    // )


    axiCrossbar.build()


    val apbDecoder = Apb3Decoder(
      master = apbBridge.io.apb,
      slaves = List(
        gpioACtrl.io.apb -> (0x00000, 4 kB),
        gpioBCtrl.io.apb -> (0x01000, 4 kB),
        uartCtrl.io.apb  -> (0x10000, 4 kB),
        timerCtrl.io.apb -> (0x20000, 4 kB),
        vgaCtrl.io.apb   -> (0x30000, 4 kB)
      )
    )
  }
  io.regOut <> axi.myAxiSlave.io.regOut.asBool
  io.gpioA          <> axi.gpioACtrl.io.gpio
  io.gpioB          <> axi.gpioBCtrl.io.gpio
  io.timerExternal  <> axi.timerCtrl.io.external
  io.uart           <> axi.uartCtrl.io.uart
  io.sdram          <> axi.sdramCtrl.io.sdram
  io.vga            <> axi.vgaCtrl.io.vga
}

//DE1-SoC
object Briey{
  def main(args: Array[String]) {
    val config = SpinalConfig()
    config.generateVerilog({
      val toplevel = new Briey(BrieyConfig.default)
      toplevel.axi.vgaCtrl.vga.ctrl.io.error.addAttribute(Verilator.public)
      toplevel.axi.vgaCtrl.vga.ctrl.io.frameStart.addAttribute(Verilator.public)
      toplevel
    })
  }
}

//DE1-SoC with memory init
object BrieyWithMemoryInit{
  def main(args: Array[String]) {
    val config = SpinalConfig()
    config.generateVerilog({
      val toplevel = new Briey(BrieyConfig.default)
      toplevel.axi.vgaCtrl.vga.ctrl.io.error.addAttribute(Verilator.public)
      toplevel.axi.vgaCtrl.vga.ctrl.io.frameStart.addAttribute(Verilator.public)
      HexTools.initRam(toplevel.axi.ram.ram, "src/main/ressource/hex/muraxDemo.hex", 0x80000000l)
      toplevel
    })
  }
}


//DE0-Nano
object BrieyDe0Nano{
  def main(args: Array[String]) {
    object IS42x160G {
      def layout = SdramLayout(
        generation = SDR,
        bankWidth   = 2,
        columnWidth = 9,
        rowWidth    = 13,
        dataWidth   = 16
      )

      def timingGrade7 = SdramTimings(
        bootRefreshCount =   8,
        tPOW             = 100 us,
        tREF             =  64 ms,
        tRC              =  60 ns,
        tRFC             =  60 ns,
        tRAS             =  37 ns,
        tRP              =  15 ns,
        tRCD             =  15 ns,
        cMRD             =   2,
        tWR              =  10 ns,
        cWR              =   1
      )
    }
    val config = SpinalConfig()
    config.generateVerilog({
      val toplevel = new Briey(BrieyConfig.default.copy(sdramLayout = IS42x160G.layout))
      toplevel
    })
  }
}



import spinal.core.sim._
object BrieySim {
  def main(args: Array[String]): Unit = {
    val simSlowDown = false
    SimConfig.allOptimisation.compile(new Briey(BrieyConfig.default)).doSimUntilVoid{dut =>
      val mainClkPeriod = (1e12/dut.config.axiFrequency.toDouble).toLong
      val jtagClkPeriod = mainClkPeriod*4
      val uartBaudRate = 115200
      val uartBaudPeriod = (1e12/uartBaudRate).toLong

      val clockDomain = ClockDomain(dut.io.axiClk, dut.io.asyncReset)
      clockDomain.forkStimulus(mainClkPeriod)

      val tcpJtag = JtagTcp(
        jtag = dut.io.jtag,
        jtagClkPeriod = jtagClkPeriod
      )

      val uartTx = UartDecoder(
        uartPin = dut.io.uart.txd,
        baudPeriod = uartBaudPeriod
      )

      val uartRx = UartEncoder(
        uartPin = dut.io.uart.rxd,
        baudPeriod = uartBaudPeriod
      )

      val sdram = SdramModel(
        dut.io.sdram,
        dut.config.sdramLayout,
        clockDomain
      )

      dut.io.coreInterrupt #= false
    }
  }
}
