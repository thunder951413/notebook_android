package io.github.notebook.android.reminder

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ReminderTest {
    @Test fun `delayed daily delivery keeps original cadence`(){val base=Calendar.getInstance().apply{set(2026,0,1,9,30,0);set(Calendar.MILLISECOND,0)}.timeInMillis;val next=Reminders.nextOccurrence(base,"daily",base+3*86_400_000+1);val cal=Calendar.getInstance().apply{timeInMillis=next};assertEquals(9,cal.get(Calendar.HOUR_OF_DAY));assertEquals(30,cal.get(Calendar.MINUTE));assertTrue(next>base+3*86_400_000)}
    @Test fun `weekly and monthly recurrence advance into future without month end drift`(){val base=Calendar.getInstance().apply{set(2026,0,31,8,0,0);set(Calendar.MILLISECOND,0)}.timeInMillis;assertTrue(Reminders.nextOccurrence(base,"weekly",base+15*86_400_000)>base+15*86_400_000);val monthly=Calendar.getInstance().apply{timeInMillis=Reminders.nextOccurrence(base,"monthly",base+32*86_400_000)};assertEquals(Calendar.MARCH,monthly.get(Calendar.MONTH));assertEquals(31,monthly.get(Calendar.DAY_OF_MONTH))}
}
