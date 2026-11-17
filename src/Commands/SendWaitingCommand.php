<?php

namespace Rishadblack\SyllooAppNative\Commands;

use Illuminate\Console\Command;
use Rishadblack\SyllooAppNative\Contracts\WaitingSender;
use Throwable;

/**
 * The background run of Sync.Schedule (Android's WorkManager boots the app's PHP and runs this): asks the app's
 * {@see WaitingSender} to send what waits. Prints RETRY when the run should be tried again later; Kotlin reads it.
 */
class SendWaitingCommand extends Command
{
    protected $signature = 'sylloo-app-native:send-waiting';

    protected $description = 'Send what waits on the phone (background run of Sync.Schedule)';

    public function handle(): int
    {
        if (! $this->laravel->bound(WaitingSender::class)) {
            $this->line('NOTHING');

            return self::SUCCESS;
        }

        try {
            $done = $this->laravel->make(WaitingSender::class)->sendWaiting();
        } catch (Throwable $e) {
            report($e);
            $done = false;
        }

        $this->line($done ? 'DONE' : 'RETRY');

        return self::SUCCESS;
    }
}
