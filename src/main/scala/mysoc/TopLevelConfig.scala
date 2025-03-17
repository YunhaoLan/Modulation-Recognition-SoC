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

case class TopLevelConfig(
    axiFrequency: HertzNumber,
    onChipRamSize: BigInt,
    uartCtrlConfig: UartCtrlMemoryMappedConfig,
    cpuPlugins: Seq[Plugin[VexRiscv]]
)

object TopLevelConfig {
  def default: TopLevelConfig = TopLevelConfig(
    axiFrequency = 50 MHz,
    onChipRamSize = 4 kB,
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
    cpuPlugins = Seq(
      new PcManagerSimplePlugin(0x80000000l, relaxedPcCalculation = false),
      new IBusCachedPlugin(
        resetVector = 0x80000000l,
        prediction = STATIC,
        config = InstructionCacheConfig(
          cacheSize          = 4096,
          bytePerLine        = 32,
          wayCount           = 1,
          addressWidth       = 32,
          cpuDataWidth       = 32,
          memDataWidth       = 32,
          catchIllegalAccess = true,
          catchAccessFault   = true,
          asyncTagMemory     = false,
          twoCycleRam        = true,
          twoCycleCache      = true
        )
      ),
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
      ),
      // Add the memory translator plugin as in the Briey example:
      new StaticMemoryTranslatorPlugin(
        ioRange = _(31 downto 28) === 0xF
      ),
      new DecoderSimplePlugin(catchIllegalInstruction = true),
      new RegFilePlugin(regFileReadyKind = plugin.SYNC, zeroBoot = false),
      new IntAluPlugin,
      new SrcPlugin(separatedAddSub = false, executeInsertion = true),
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
      new BranchPlugin(earlyBranch = false, catchAddressMisaligned = true),
      new CsrPlugin(
        config = CsrPluginConfig(
          catchIllegalAccess   = false,
          mvendorid            = null,
          marchid              = null,
          mimpid               = null,
          mhartid              = null,
          misaExtensionsInit   = 66,
          misaAccess           = CsrAccess.NONE,
          mtvecAccess          = CsrAccess.NONE,
          mtvecInit            = 0x80000020l,
          mepcAccess           = CsrAccess.READ_WRITE,
          mscratchGen          = false,
          mcauseAccess         = CsrAccess.READ_ONLY,
          mbadaddrAccess       = CsrAccess.READ_ONLY,
          mcycleAccess         = CsrAccess.NONE,
          minstretAccess       = CsrAccess.NONE,
          ecallGen             = false,
          wfiGenAsWait         = false,
          ucycleAccess         = CsrAccess.NONE,
          uinstretAccess       = CsrAccess.NONE
        )
      ),
    )
  )
}

