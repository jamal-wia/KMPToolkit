package io.github.jamal_wia.kmptoolkit.scheduler

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 19 (bounded resources, sibling hunt after R18-S1): the Android scheduler instance and the process-wide dispatch
 * registration retain no collection that could grow with the number of distinct alarm ids, and the registration is a single
 * replaced slot. 01-overview: "The scheduler is stateless. It cannot list armed alarms, cannot tell you whether an id is
 * armed". A new retained collection fails this guard until its bound is argued.
 */
class SchedulerRetainedStateGuardAdversarialTest {

    private fun collectionFields(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) && !it.name.contains("registration") }
        .filter { holdsCollection(it) }
        .map { it.name }
        .toSet()

    private fun holdsCollection(field: Field): Boolean {
        val t: Class<*> = field.type
        return Collection::class.java.isAssignableFrom(t) || Map::class.java.isAssignableFrom(t) || t.isArray
    }

    @Test
    fun `the Android scheduler instance holds no collection`() {
        assertEquals(emptySet(), collectionFields(AndroidAlarmScheduler::class.java))
    }

    @Test
    fun `the dispatch registration is one replaced slot and the alarm receiver holds no state`() {
        assertEquals(emptySet(), collectionFields(AlarmReceiver::class.java))
        val registration: Field = AlarmDispatch::class.java.declaredFields.single { it.name == "registration" }
        assertEquals(kotlinx.coroutines.flow.MutableStateFlow::class.java, registration.type)
        assertEquals(emptySet(), collectionFields(AlarmDispatch.Registration::class.java).filter { it != "handlerProvider" }.toSet())
    }
}
