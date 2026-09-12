plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the tracker is a thin wrapper over the framework's own
    // activity lifecycle callbacks, so testing it without a real Application would test the wrapper
    // against a mock of the thing that actually decides its behaviour.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Activity")
    pomDescription.set(
        "Scoped access to the Android activity that is resumed right now, for code that is not " +
            "itself an activity: an ActivityAccess you create from your Application, with no " +
            "getter to leak through, a weak reference cleared by the framework's own lifecycle " +
            "callbacks, and a predicate deciding which activities count — so a photo picker or a " +
            "sign-in screen resuming inside your process does not become 'the' activity. Pick " +
            "this if a controller, a permission launcher, or anything else has to reach a window " +
            "whose identity changes on every rotation. Android only: there is no iOS counterpart " +
            "to an Activity."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.activity"
}
