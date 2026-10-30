package dev.pixflow.lab.worker;

@FunctionalInterface
public interface AfterPspCallHook {
    void afterCall();
}
