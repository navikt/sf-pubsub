/*package no.nav.sf.pdl.kafka.gui

import no.nav.sf.pubsub.gui.Gui
import no.nav.sf.pubsub.readResourceFile
import no.nav.sf.pubsub.reduceByWhitelist
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Test

class GuiTest {

    private val exampleTestDataIATask = readResourceFile("/testdata-IA-task.json")

    // To investigate current state, not suitable for unit test:
    private val whitelist = readResourceFile("/whitelist/task/dev.json")

    @Test
    fun testExpectedResult() {
//        val whitelist = """
//            {
//              "hentPerson": {
//                  "bostedsadresse": {
//                    "vegadresse" : {
//                        "husnummer": "ALL",
//                        "husbokstav": "ALL"
//                    }
//                  },
//                  "foedsel": {
//                    "metadata": {
//                        "master": "ALL"
//                    }
//                  }
//              }
//            }
//        """

        // Will update Gui models:
        reduceByWhitelist(exampleTestDataIATask, whitelist)

        val response = Gui.guiHandler.invoke(Request(Method.GET, "", ""))

        println(response.bodyString())

        // val expectedResultPage = readResourceFile("/GuiPageFromTestCase.html").replace("\n", System.lineSeparator())

        // Assertions.assertEquals(expectedResultPage, response.body.toString())
    }
}*/
