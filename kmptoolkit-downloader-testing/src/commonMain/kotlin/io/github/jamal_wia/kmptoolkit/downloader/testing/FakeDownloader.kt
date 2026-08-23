package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit
import io.github.jamal_wia.kmptoolkit.downloader.Downloader
import io.github.jamal_wia.kmptoolkit.downloader.GroupDownloadState
import io.github.jamal_wia.kmptoolkit.downloader.ResourceGroup
import io.github.jamal_wia.kmptoolkit.downloader.UnitDownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The one [Downloader] fake every consumer test should use, instead of hand-copying its own.
 *
 * A test drives it from outside: [emit] pushes a per-unit state the way a real engine would,
 * [setGroupState] does the same for a group, [availableUnitIds] and [availableGroupKeys] decide
 * what [isAvailable] answers, and every mutating call is recorded ([ensuredUnits], [cancelledUnits],
 * [cancelledGroups], [cancelAllCalls]) so assertions read what happened rather than re-deriving it.
 *
 * Per-unit flows deliberately mirror a real engine's cold-until-emitted shape via a replay-1
 * [MutableSharedFlow]: a collector sees nothing until the test emits, exactly like a fresh unit
 * nobody has asked about — which is what lets UI tests exercise the "initial frame before the first
 * emission" path a real engine also has.
 *
 * @param onCancelUnit hook invoked inside [cancelDownload] — for tests that assert the ORDER of
 *   calls across several fakes by appending to one shared event list.
 */
public class FakeDownloader(
    availableUnitIds: Set<String> = emptySet(),
    private val onCancelUnit: (DownloadUnit) -> Unit = {},
) : Downloader {

    public val availableUnitIds: MutableSet<String> = availableUnitIds.toMutableSet()
    public val availableGroupKeys: MutableSet<String> = mutableSetOf()

    public val ensuredUnits: MutableList<DownloadUnit> = mutableListOf()
    public val ensuredGroups: MutableList<ResourceGroup> = mutableListOf()
    public val cancelledUnits: MutableList<DownloadUnit> = mutableListOf()
    public val cancelledGroups: MutableList<ResourceGroup> = mutableListOf()
    public var cancelAllCalls: Int = 0
        private set
    public var hasActiveUnitDownloadAnswer: Boolean = false

    private val unitFlows: MutableMap<String, MutableSharedFlow<UnitDownloadState>> = mutableMapOf()
    private val groupFlows: MutableMap<String, MutableStateFlow<GroupDownloadState>> =
        mutableMapOf()

    /** Pushes [state] to [unit]'s flow, the way a real engine would during a real download. */
    public fun emit(unit: DownloadUnit, state: UnitDownloadState) {
        unitFlowFor(unit).tryEmit(state)
    }

    /** Sets [group]'s current state, the way a real engine would during a real download. */
    public fun setGroupState(group: ResourceGroup, state: GroupDownloadState) {
        groupFlowFor(group).value = state
    }

    override fun isAvailable(group: ResourceGroup): Boolean = group.key in availableGroupKeys

    override fun isAvailable(unit: DownloadUnit): Boolean = unit.id in availableUnitIds

    override fun pathOf(unit: DownloadUnit): String = "/fake/${unit.relativePath}"

    override fun downloadState(group: ResourceGroup): StateFlow<GroupDownloadState> =
        groupFlowFor(group)

    override suspend fun ensureAvailable(group: ResourceGroup) {
        ensuredGroups += group
    }

    override fun cancelDownload(group: ResourceGroup) {
        cancelledGroups += group
    }

    override suspend fun cancelAllDownloads() {
        cancelAllCalls++
    }

    override fun retryDownload(group: ResourceGroup): Unit = Unit

    override suspend fun ensureAvailable(unit: DownloadUnit) {
        ensuredUnits += unit
    }

    override fun cancelDownload(unit: DownloadUnit) {
        cancelledUnits += unit
        onCancelUnit(unit)
    }

    override suspend fun hasActiveUnitDownload(): Boolean = hasActiveUnitDownloadAnswer

    override fun unitDownloadStateFlow(unit: DownloadUnit): Flow<UnitDownloadState> =
        unitFlowFor(unit).asSharedFlow()

    private fun unitFlowFor(unit: DownloadUnit): MutableSharedFlow<UnitDownloadState> =
        unitFlows.getOrPut(unit.id) { MutableSharedFlow(replay = 1) }

    private fun groupFlowFor(group: ResourceGroup): MutableStateFlow<GroupDownloadState> =
        groupFlows.getOrPut(group.key) { MutableStateFlow(GroupDownloadState.Idle) }
}
