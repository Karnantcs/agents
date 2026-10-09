package dev.agentbox.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActivityLogTest {

    @Test
    void linksADelegationToTheSendersTaskAndTracksBusyState() {
        ActivityLog log = new ActivityLog();
        ActivityLog.Entry lead = log.open("user", "lead", "ask the worker", null);
        log.markWorking(lead.id());
        assertThat(log.live("lead").availability()).isEqualTo("busy");
        assertThat(log.live("lead").task()).isEqualTo("ask the worker");
        assertThat(log.live("lead").from()).isEqualTo("user");

        ActivityLog.Entry worker = log.open("lead", "worker", "write hello.txt", null);
        assertThat(worker.id()).isGreaterThan(lead.id());
        assertThat(log.list(null).get(0).id()).isEqualTo(worker.id());
        assertThat(log.list(null).get(0).parentId()).isEqualTo(lead.id());
        assertThat(log.list(null).get(0).status()).isEqualTo("sent");
        assertThat(log.list("lead")).extracting(ActivityLog.View::to).containsExactly("worker", "lead");
        assertThat(log.list("worker")).extracting(ActivityLog.View::to).containsExactly("worker");

        log.markWorking(worker.id());
        log.complete(worker.id(), "wrote it");
        assertThat(log.live("worker").availability()).isEqualTo("idle");
        assertThat(log.live("lead").availability()).isEqualTo("busy");
        ActivityLog.View done = log.list("worker").get(0);
        assertThat(done.status()).isEqualTo("done");
        assertThat(done.reply()).isEqualTo("wrote it");
        assertThat(done.durationMillis()).isNotNull().isGreaterThanOrEqualTo(0);

        log.fail(lead.id(), "agent lead is exited");
        assertThat(log.live("lead").availability()).isEqualTo("idle");
        assertThat(log.list(null).get(1).status()).isEqualTo("error");
        assertThat(log.list(null).get(1).reply()).isEqualTo("agent lead is exited");
    }

    @Test
    void keepsTheNewestFiveHundredEntries() {
        ActivityLog log = new ActivityLog();
        for (int i = 1; i <= ActivityLog.LIMIT + 25; i++) {
            ActivityLog.Entry entry = log.open("user", "lead", "task " + i, null);
            log.markWorking(entry.id());
            log.complete(entry.id(), "ok");
        }
        assertThat(log.list(null)).hasSize(ActivityLog.LIMIT);
        assertThat(log.list(null).get(0).message()).isEqualTo("task " + (ActivityLog.LIMIT + 25));
        assertThat(log.list(null).get(ActivityLog.LIMIT - 1).message()).isEqualTo("task 26");
    }

    @Test
    void doesNotDropAnInFlightTaskToMakeRoom() {
        ActivityLog log = new ActivityLog();
        ActivityLog.Entry current = log.open("user", "lead", "still going", null);
        log.markWorking(current.id());
        for (int i = 0; i < ActivityLog.LIMIT; i++) {
            ActivityLog.Entry entry = log.open("user", "worker", "done " + i, null);
            log.complete(entry.id(), "ok");
        }
        assertThat(log.list(null)).hasSize(ActivityLog.LIMIT);
        assertThat(log.live("lead").task()).isEqualTo("still going");
        assertThat(log.list("lead")).extracting(ActivityLog.View::message).contains("still going");
    }

    @Test
    void recordsToolCallsUnderTheTaskWithoutMarkingTheAgentBusy() {
        ActivityLog log = new ActivityLog();
        ActivityLog.Entry task = log.open("user", "worker", "write the file", null);
        log.markWorking(task.id());
        log.complete(task.id(), "done");
        log.recordTool("worker", "run_shell", "command: ls", "a.txt", task.id());
        log.recordTool("worker", "read_file", "path: missing", "error: file not found", task.id());

        assertThat(log.live("worker").availability()).isEqualTo("idle");
        assertThat(log.list("worker")).extracting(ActivityLog.View::kind).containsExactly("tool", "tool", "message");
        ActivityLog.View failed = log.list("worker").get(0);
        assertThat(failed.to()).isEqualTo("read_file");
        assertThat(failed.status()).isEqualTo("error");
        assertThat(failed.parentId()).isEqualTo(task.id());
        assertThat(log.list("worker").get(2).kind()).isEqualTo("message");
    }
}
