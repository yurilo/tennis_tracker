package com.tennis.tracker.data

import com.tennis.tracker.data.remote.EspnWatchApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnWatchApiTest {

    private val api = EspnWatchApi()

    private val sampleWatchJson = """
    {
      "page": {
        "buckets": [
          {
            "name": "Live US Open",
            "contents": [
              {
                "id": "d174bd1f-98f7-490e-b285-d75124819741",
                "name": "Khachanov vs. Bonzi (Men's Third Round)",
                "shortName": "Khachanov vs. Bonzi",
                "subtitle": "ESPN Unlimited • US Open",
                "status": "live",
                "eventId": 100215690,
                "streams": [
                  {
                    "links": {
                      "appPlay": "sportscenter://x-callback-url/showWatchStream?playID=d174bd1f-98f7-490e-b285-d75124819741",
                      "web": "https://www.espn.com/watch/player?id=d174bd1f-98f7-490e-b285-d75124819741"
                    }
                  }
                ]
              },
              {
                "id": "5aa19aba-8839-4188-916d-0a0f7b8d161b",
                "name": "Court 4",
                "shortName": "Court 4",
                "subtitle": "ESPN Unlimited • US Open",
                "status": "live",
                "streams": [
                  {
                    "links": {
                      "web": "https://www.espn.com/watch/player?id=5aa19aba-8839-4188-916d-0a0f7b8d161b"
                    }
                  }
                ]
              },
              {
                "id": "non-tennis-item-123",
                "name": "VMI vs. Virginia Tech",
                "subtitle": "ACCN • NCAA Football",
                "status": "live"
              }
            ]
          }
        ]
      }
    }
    """.trimIndent()

    @Test
    fun `parseWatchStreamsJson should correctly parse tennis and US Open streams`() {
        val streams = api.parseWatchStreamsJson(sampleWatchJson)

        assertEquals("Should parse 2 tennis streams and exclude football", 2, streams.size)

        val matchStream = streams.firstOrNull { it.watchId == "d174bd1f-98f7-490e-b285-d75124819741" }
        assertNotNull("Should find Khachanov vs Bonzi stream", matchStream)
        assertEquals("Khachanov vs. Bonzi (Men's Third Round)", matchStream?.name)
        assertEquals("sportscenter://x-callback-url/showWatchStream?playID=d174bd1f-98f7-490e-b285-d75124819741", matchStream?.appPlayUrl)
        assertEquals("https://www.espn.com/watch/player?id=d174bd1f-98f7-490e-b285-d75124819741", matchStream?.webUrl)
        assertEquals(100215690L, matchStream?.eventId)

        val courtStream = streams.firstOrNull { it.watchId == "5aa19aba-8839-4188-916d-0a0f7b8d161b" }
        assertNotNull("Should find Court 4 stream", courtStream)
        assertEquals("Court 4", courtStream?.name)
        assertEquals("Court 4", courtStream?.court)
        assertTrue(courtStream?.webUrl?.contains("5aa19aba") == true)
    }

    @Test
    fun `parseScheduleHtml should extract and parse streams from espnfitt html`() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>ESPN Watch Schedule</title></head>
            <body>
            <script>
            window['__espnfitt__']={"page":{"content":{"watch":{"arngs":[{"nme":"Tennis","sctgys":[{"nme":"US Open","arngs":[{"id":"5e11332f-2e81-4516-9f6f-7dfda1ed705d","nme":"(31) Bergs vs. van de Zandschulp (Men's Third Round)","fnme":"Bergs vs. van de Zandschulp","tp":"replay","nb":"sportscenter://x-callback-url/showWatchStream?playID=5e11332f-2e81-4516-9f6f-7dfda1ed705d"}]}]}]}}}};
            </script>
            </body>
            </html>
        """.trimIndent()

        val streams = api.parseScheduleHtml(sampleHtml)
        assertEquals("Should parse 1 stream from schedule html", 1, streams.size)
        val stream = streams[0]
        assertEquals("5e11332f-2e81-4516-9f6f-7dfda1ed705d", stream.watchId)
        assertEquals("(31) Bergs vs. van de Zandschulp (Men's Third Round)", stream.name)
        assertEquals("Bergs vs. van de Zandschulp", stream.shortName)
        assertEquals("sportscenter://x-callback-url/showWatchStream?playID=5e11332f-2e81-4516-9f6f-7dfda1ed705d", stream.appPlayUrl)
        assertEquals("https://www.espn.com/watch/player?id=5e11332f-2e81-4516-9f6f-7dfda1ed705d", stream.webUrl)
    }
}

