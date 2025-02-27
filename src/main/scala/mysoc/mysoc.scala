// package mysoc

// import spinal.core._
// import vexriscv.demo.Murax
// import vexriscv.demo.MuraxConfig
// import myplugin.SscaPlugin
// import myacc.SscaAccelerator

// class MySoCTop extends Component {
//   val io = new Bundle {
//     // Expose the SSCA status externally if needed:
//     val sscaStatusOut = out Bool()
//     // Other I/O for your SoC can be added here
//   }

//   // Create a custom Murax configuration, adding your SscaPlugin:
//   val muraxConfig = MuraxConfig.default.copy(
//     // Adjust configuration parameters if necessary
//   )
//   // Add your custom plugin into the CPU plugins list:
//   val sscaPlugin = new SscaPlugin
//   muraxConfig.cpuPlugins += sscaPlugin
//   val murax = new Murax(muraxConfig)

//   // Instantiate your accelerator module using the BlackBox wrapper.
//   val SscaAccelerator = SscaAccelerator()

//   // Connect the accelerator's status signal (from the Verilog module) to the plugin's CSR register.
//   sscaPlugin.sscaCsr := !SscaAccelerator.io.done

//   // Optionally, expose the status register externally:
//   io.sscaStatusOut := sscaPlugin.sscaCsr
// }
