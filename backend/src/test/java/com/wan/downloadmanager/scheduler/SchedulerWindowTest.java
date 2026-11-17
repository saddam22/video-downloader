package com.wan.downloadmanager.scheduler;

import com.wan.downloadmanager.entity.Schedule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerWindowTest {
    static Schedule s(String start, String end, String days, String date) {
        Schedule s = new Schedule();
        s.setStartTime(start);
        s.setEndTime(end);
        s.setDaysOfWeek(days);
        s.setOneShotDate(date);
        return s;
    }

    @Test
    void daytimeWindow() {
        Schedule w = s("09:00", "17:00", "MON,TUE", null);
        assertThat(SchedulerService.inWindow(w, LocalDateTime.parse("2026-11-16T09:00"))).isTrue(); // Monday
        assertThat(SchedulerService.inWindow(w, LocalDateTime.parse("2026-11-16T17:00"))).isFalse();
        assertThat(SchedulerService.inWindow(w, LocalDateTime.parse("2026-11-18T10:00"))).isFalse(); // Wednesday
    }

    @Test
    void overnightWindowBelongsToTheStartDay() {
        Schedule w = s("23:00", "06:00", "FRI", null);
        assertThat(SchedulerService.inWindow(w, LocalDateTime.parse("2026-11-20T23:30"))).isTrue();  // Fri night
        assertThat(SchedulerService.inWindow(w, LocalDateTime.parse("2026-11-21T05:59"))).isTrue();  // Sat early
        assertThat(SchedulerService.inWindow(w, LocalDateTime.parse("2026-11-21T23:30"))).isFalse(); // Sat night
    }

    @Test
    void oneShotAndStartOnly() {
        Schedule once = s("08:00", null, "MON,TUE,WED,THU,FRI,SAT,SUN", "2026-12-01");
        assertThat(SchedulerService.inWindow(once, LocalDateTime.parse("2026-12-01T08:02"))).isTrue();
        assertThat(SchedulerService.inWindow(once, LocalDateTime.parse("2026-12-01T08:06"))).isFalse();
        assertThat(SchedulerService.inWindow(once, LocalDateTime.parse("2026-12-02T08:02"))).isFalse();
    }
}
