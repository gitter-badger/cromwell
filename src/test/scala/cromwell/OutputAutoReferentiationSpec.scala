package cromwell

import akka.testkit._
import cromwell.binding.values.WdlFloat
import cromwell.util.SampleWdl

import scala.language.postfixOps

class OutputAutoReferentiationSpec extends CromwellTestkitSpec {
  "A task with outputs which reference other outputs" should {
    "run without let or hindrance" in {
      runWdlAndAssertOutputs(
        sampleWdl = SampleWdl.InputsAndOutputsAutoReferentiation,
        eventFilter = EventFilter.info(pattern = s"starting calls: wf.golden_pie", occurrences = 1),
        expectedOutputs = Map(
          "wf.golden_pie.Au" -> WdlFloat(1.6180339887),
          "wf.golden_pie.doubleAu" -> WdlFloat(1.6180339887 * 2),
          "wf.golden_pie.tauValue" -> WdlFloat(3.1415926 * 2)
        )
      )
    }
  }
}
