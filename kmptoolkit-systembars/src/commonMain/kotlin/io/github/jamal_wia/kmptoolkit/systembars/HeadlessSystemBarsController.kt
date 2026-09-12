package io.github.jamal_wia.kmptoolkit.systembars

/**
 * Creates a [SystemBarsController] with the full layer model and no window behind it — the no-op
 * double to use from **production** source, where a test artifact cannot go.
 *
 * The case it exists for is `@Preview`. A screen that uses [SystemBarsEffect], or that resolves a
 * controller out of a DI container, needs one to exist or the preview throws instead of rendering —
 * and `@Preview` functions compile into your release source set, so
 * `RecordingSystemBarsController` from `kmptoolkit-systembars-testing` is not available to them.
 * Without this, every consumer with previews hand-rolls the same empty `SystemBarsController`.
 * The same applies to a screenshot harness, a design gallery, or any other host that renders real
 * screens with no system bars to style.
 *
 * It is headless, not inert: the layer stack behaves exactly as it does on a device — overrides
 * stack, the newest wins a shared axis, an override claims only the axes it names, and releasing one
 * restores whatever is underneath it at that moment. Only the final push to a window is skipped. A
 * preview of a screen that reads [SystemBarsController.config] therefore sees the value it would see
 * on a phone, rather than a permanent default.
 *
 * For asserting what a screen *did* — which configurations it pushed, whether it left a layer
 * behind — use `RecordingSystemBarsController` instead. This one records nothing.
 *
 * ```kotlin
 * @Preview
 * @Composable
 * private fun PhotoViewerPreview() {
 *     KoinApplication(configuration = koinConfiguration {
 *         modules(module { single<SystemBarsController> { createHeadlessSystemBarsController() } })
 *     }) {
 *         PhotoViewer(photo = SamplePhoto)
 *     }
 * }
 * ```
 *
 * @param initialConfig the base configuration to start from.
 */
public fun createHeadlessSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): SystemBarsController = HeadlessSystemBarsController(initialConfig)

internal class HeadlessSystemBarsController(
    initialConfig: SystemBarsConfig,
) : LayeredSystemBarsController(initialConfig) {

    override fun applyToPlatform(config: SystemBarsConfig) {
        // Nothing to push: there is no window on the other side of this controller.
    }
}
