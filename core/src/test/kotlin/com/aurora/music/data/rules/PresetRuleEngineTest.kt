package com.aurora.music.data.rules

import com.aurora.music.data.routes.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PresetRuleEngineTest {
    private val key = RouteIdentity.key(2, "", true, true)!!
    private val route = RouteObservation(1, ProcessingRoute(ProcessingRouteKind.ANDROID, key, "Speaker"))
    private val context = PresetRuleObservation(1, PresetPlaybackContext(active = true, mediaId = "track-one",
        source = RuleSource.STREAM, providerId = "provider-one", providerLabel = "Music server",
        albumId = "album-one", albumName = "Night Walk", genres = setOf("Ambient", "Electronic"),
        playlistId = "playlist-one", playlistName = "Evening", sampleRateHz = 96000, codec = "FLAC", container = "flac",
        androidAuto = false, cast = false))

    private fun condition(field: RuleField, vararg values: String) = PresetRuleCondition(field, values.toList())
    private fun rule(name: String = "Rule", priority: Int = 0, conditions: List<PresetRuleCondition> =
        listOf(condition(RuleField.GENRE, "ambient")), match: RuleMatch = RuleMatch.ALL) =
        PresetRule(UUID.randomUUID().toString(), name, UUID.randomUUID().toString(), priority = priority, match = match, conditions = conditions)
    private fun input(vararg rules: PresetRule) = PresetRuleInput(context, route, PresetRuleSet(true, rules = rules.toList()), ProcessingRouteRules())

    @Test fun priorityWinsAndListOrderBreaksTies() {
        val first = rule("First", 4)
        val second = rule("Second", 5)
        val third = rule("Third", 5)
        assertEquals(second, PresetRuleEngine.winner(input(first, second, third)))
        assertEquals(third, PresetRuleEngine.winner(input(third, first, second)))
        assertEquals(first, PresetRuleEngine.winner(input(first, second.copy(enabled = false), third.copy(enabled = false))))
        val preview = PresetRuleEngine.preview(input(first, second, third))
        assertEquals(listOf("Second", "Third", "First"), preview.results.map { it.rule.name })
        assertTrue(preview.results.all { it.matched })
    }

    @Test fun allAndAnyAreExplicitWithoutUnknownMatching() {
        val matches = condition(RuleField.GENRE, "ambient")
        val fails = condition(RuleField.ALBUM_NAME, "Other album")
        assertNull(PresetRuleEngine.winner(input(rule(conditions = listOf(matches, fails)))))
        assertNotNull(PresetRuleEngine.winner(input(rule(conditions = listOf(matches, fails), match = RuleMatch.ANY))))
        val unknown = input(rule(conditions = listOf(condition(RuleField.PROVIDER, "provider-one"))))
            .copy(observation = context.copy(playback = context.playback.copy(providerId = null)))
        assertNull(PresetRuleEngine.winner(unknown))
        assertEquals(RuleConditionState.UNKNOWN, PresetRuleEngine.preview(unknown).results.single().conditions.single().state)
    }

    @Test fun everySupportedPredicateUsesActualContext() {
        val conditions = listOf(condition(RuleField.ROUTE, key), condition(RuleField.HEADPHONES, "Open back"),
            condition(RuleField.SOURCE, RuleSource.STREAM.name), condition(RuleField.PROVIDER, "provider-one"),
            condition(RuleField.ALBUM_ID, "album-one"), condition(RuleField.ALBUM_NAME, "night walk"),
            condition(RuleField.GENRE, "ambient"), condition(RuleField.PLAYLIST_ID, "playlist-one"),
            condition(RuleField.PLAYLIST_NAME, "evening"), PresetRuleCondition(RuleField.SAMPLE_RATE, minRateHz = 88200, maxRateHz = 96000),
            condition(RuleField.CODEC, "flac"), condition(RuleField.CONTAINER, "FLAC"), condition(RuleField.CONTEXT, RulePlaybackMode.LOCAL.name))
        val active = input(rule(conditions = conditions)).copy(outputRules = ProcessingRouteRules(headphones = mapOf(key to "Open back")))
        assertNotNull(PresetRuleEngine.winner(active))
        assertTrue(PresetRuleEngine.preview(active).results.single().conditions.all { it.state == RuleConditionState.MATCH })
        val otherProvider = active.copy(observation = context.copy(playback = context.playback.copy(providerId = "provider-two")))
        assertNull(PresetRuleEngine.winner(otherProvider))
    }

    @Test fun identifiersAreExactWhileNamesIgnoreCase() {
        assertNotNull(PresetRuleEngine.winner(input(rule(conditions = listOf(condition(RuleField.ALBUM_NAME, "NIGHT WALK"))))))
        assertNull(PresetRuleEngine.winner(input(rule(conditions = listOf(condition(RuleField.ALBUM_ID, "ALBUM-ONE"))))))
        assertNull(PresetRuleEngine.winner(input(rule(conditions = listOf(condition(RuleField.PROVIDER, "PROVIDER-ONE"))))))
    }

    @Test fun manualHoldAndOutputHoldBothBlockAutomaticRules() {
        val active = input(rule())
        assertNull(PresetRuleEngine.winner(active.copy(rules = active.rules.copy(manualHold = true))))
        assertNull(PresetRuleEngine.winner(active.copy(outputRules = ProcessingRouteRules(manual = setOf(key)))))
        assertTrue(PresetRuleEngine.preview(active.copy(rules = active.rules.copy(manualHold = true))).pausedReason!!.contains("Manual"))
    }

    @Test fun castCanMatchPreviewButCannotApplyLocalProcessing() {
        val castRule = rule(conditions = listOf(condition(RuleField.CONTEXT, RulePlaybackMode.CAST.name)))
        val active = input(castRule).copy(observation = context.copy(playback = context.playback.copy(cast = true)),
            route = route.copy(route = ProcessingRoute(ProcessingRouteKind.CAST)))
        assertEquals(castRule, PresetRuleEngine.preview(active).winner)
        assertNull(PresetRuleEngine.winner(active))
        assertNotNull(PresetRuleEngine.preview(active).pausedReason)
    }

    @Test fun unknownContextIsNotEquivalentToLocalPlayback() {
        val local = rule(conditions = listOf(condition(RuleField.CONTEXT, RulePlaybackMode.LOCAL.name)))
        val missing = input(local).copy(observation = context.copy(playback = context.playback.copy(androidAuto = null)))
        assertEquals(RuleConditionState.UNKNOWN, PresetRuleEngine.preview(missing).results.single().conditions.single().state)
        val auto = local.copy(conditions = listOf(condition(RuleField.CONTEXT, RulePlaybackMode.ANDROID_AUTO.name)))
        val partial = input(auto).copy(observation = context.copy(playback = context.playback.copy(cast = true, androidAuto = null)))
        assertEquals(RuleConditionState.UNKNOWN, PresetRuleEngine.preview(partial).results.single().conditions.single().state)
    }

    @Test fun androidAutoIsAnObservedContextCondition() {
        val selected = rule(conditions = listOf(condition(RuleField.CONTEXT, RulePlaybackMode.ANDROID_AUTO.name)))
        assertNull(PresetRuleEngine.winner(input(selected)))
        assertEquals(selected, PresetRuleEngine.winner(input(selected).copy(observation = context.copy(playback = context.playback.copy(androidAuto = true)))))
    }

    @Test fun staleTrackRouteRulesHeadphonesAndFreezeCannotCommit() {
        val original = input(rule())
        assertTrue(PresetRuleEngine.mayCommit(original, original))
        val changed = listOf(
            original.copy(observation = original.observation.copy(generation = 2)),
            original.copy(observation = original.observation.copy(playback = context.playback.copy(mediaId = "track-two"))),
            original.copy(route = route.copy(generation = 2)),
            original.copy(rules = original.rules.copy(manualHold = true)),
            original.copy(rules = original.rules.copy(rules = emptyList())),
            original.copy(outputRules = ProcessingRouteRules(headphones = mapOf(key to "Closed back"))),
            original.copy(observation = original.observation.copy(frozen = true)),
        )
        changed.forEach { assertFalse(PresetRuleEngine.mayCommit(original, it)) }
        val frozen = original.copy(observation = original.observation.copy(frozen = true))
        assertFalse(PresetRuleEngine.mayCommit(frozen, frozen))
        assertNull(PresetRuleEngine.winner(frozen))
    }

    @Test fun contextMonitorKeepsFreezeAcrossServiceUpdates() {
        val monitor = PresetRuleContextMonitor()
        monitor.publish(context.playback)
        val initial = monitor.current
        monitor.publish(context.playback)
        assertEquals(initial, monitor.current)
        monitor.setFrozen(true)
        monitor.publish(context.playback.copy(mediaId = "track-two"))
        assertTrue(monitor.current.frozen)
        assertTrue(monitor.current.generation > initial.generation)
        monitor.setFrozen(false)
        assertFalse(monitor.current.frozen)
    }

    @Test fun unavailableRateAndPlaylistDoNotUseStaleOrZeroValues() {
        val selected = rule(conditions = listOf(PresetRuleCondition(RuleField.SAMPLE_RATE, maxRateHz = 48000)))
        val unknown = input(selected).copy(observation = context.copy(playback = context.playback.copy(sampleRateHz = 0)))
        assertNull(PresetRuleEngine.winner(unknown))
        assertEquals(RuleConditionState.UNKNOWN, PresetRuleEngine.preview(unknown).results.single().conditions.single().state)
        val playlist = input(rule(conditions = listOf(condition(RuleField.PLAYLIST_ID, "playlist-one"))))
            .copy(observation = context.copy(playback = context.playback.copy(playlistId = null)))
        assertNull(PresetRuleEngine.winner(playlist))
    }

    @Test fun inactiveBypassAndAmbiguousRoutesNeverApply() {
        val original = input(rule())
        assertNull(PresetRuleEngine.winner(original.copy(observation = context.copy(playback = context.playback.copy(active = false)))))
        assertNull(PresetRuleEngine.winner(original.copy(rules = original.rules.copy(enabled = false))))
        assertNull(PresetRuleEngine.winner(original.copy(route = route.copy(route = route.route.copy(key = null)))))
        ProcessingRouteKind.entries.filter { it != ProcessingRouteKind.ANDROID }.forEach {
            assertNull(PresetRuleEngine.winner(original.copy(route = route.copy(route = route.route.copy(kind = it)))))
        }
    }
}
