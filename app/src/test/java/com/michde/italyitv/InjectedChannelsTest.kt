package com.michde.italyitv

import com.michde.italyitv.core.NameTools
import com.michde.italyitv.data.epg.EpgIndex
import com.michde.italyitv.data.model.Category
import com.michde.italyitv.data.remote.InjectedChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectedChannelsTest {

    @Test
    fun testNewDaddyChannelsPresent() {
        val channels = InjectedChannels.list
        val skySport252 = channels.firstOrNull { it.url == "https://dlive.sx/watch.php?id=3" }
        val skySport253 = channels.firstOrNull { it.url == "https://dlive.sx/watch.php?id=2" }
        val skySportArena = channels.firstOrNull { it.url == "https://dlive.sx/watch.php?id=462" }

        assertNotNull("Sky Sport 252 should be present", skySport252)
        assertEquals("Sky Sport 252 (Daddy)", skySport252?.name)
        assertEquals(Category.SPORT, NameTools.classify(skySport252!!.name))

        assertNotNull("Sky Sport 253 should be present", skySport253)
        assertEquals("Sky Sport 253 (Daddy)", skySport253?.name)
        assertEquals(Category.SPORT, NameTools.classify(skySport253!!.name))

        assertNotNull("Sky Sport Arena should be present", skySportArena)
        assertEquals("Sky Sport Arena (Daddy)", skySportArena?.name)
        assertEquals(Category.SPORT, NameTools.classify(skySportArena!!.name))
    }

    @Test
    fun testEpgMatchKeys() {
        assertEquals("skysport252", NameTools.matchKey("Sky Sport 252 (Daddy)"))
        assertEquals("skysport253", NameTools.matchKey("Sky Sport 253 (Daddy)"))
        assertEquals("skysportarena", NameTools.matchKey("Sky Sport Arena (Daddy)"))
    }
}
