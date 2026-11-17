<?php

namespace Rishadblack\SyllooAppNative\Contracts;

/**
 * Sends what waits on the phone (the base's waiting list, in order, with its one-use keys) for the background run of
 * Sync.Schedule (plan §7.7). The app binds its own; the package's command `sylloo-app-native:send-waiting` calls it.
 */
interface WaitingSender
{
    /**
     * Send what waits. True when nothing is left to send (or what is left can not be sent ever); false when the run
     * should be tried again later (no internet, the office did not answer).
     */
    public function sendWaiting(): bool;
}
