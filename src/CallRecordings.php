<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Data\CallRecordingFile;
use Rishadblack\SyllooAppNative\Events\CallsFolderAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * The phone's own recordings of work calls (crm-call-recording-plan.md §5 R2, CR3): bridge functions Calls.Folder,
 * Calls.FolderStatus, Calls.Recording and Calls.RecordingDrop. Off unless the app turns `features.calls_recording` on:
 * off, nothing is asked of the phone. It needs no Android permission: the person chooses the folder their phone's call
 * recorder writes to once (Android's folder chooser), and only that folder is read.
 *
 * For one saved work call, {@see recording()} finds that call's file (by the time in its name, else by when the file
 * was closed; a file with another person's number or any doubt is never taken) and hands back an app-private copy
 * (AAC re-wrapped as .mp4; mp3 / wav / ogg as they are) with its size and SHA-256. Nothing is sent from here: the app
 * sends it (CRM's `crm/call-recording`) and then drops the copy with {@see drop()}. Nothing throws.
 */
class CallRecordings
{
    public const EXTENSIONS = ['mp4', 'mp3', 'wav', 'ogg'];

    public function enabled(): bool
    {
        return (bool) config('sylloo-app-native.features.calls_recording', false);
    }

    /**
     * Opens Android's folder chooser; the answer comes as {@see CallsFolderAnswered}. False when nothing was asked.
     */
    public function chooseFolder(?string $id = null): bool
    {
        if (! $this->enabled()) {
            return false;
        }

        return (Bridge::call('Calls.Folder', array_filter(['id' => $id]))['status'] ?? null) === 'asking';
    }

    /**
     * Lets the folder go and removes the copies not sent yet (recordings switched off, signed out, released).
     */
    public function forgetFolder(): bool
    {
        if (! $this->enabled()) {
            return false;
        }

        return (Bridge::call('Calls.Folder', ['forget' => true])['status'] ?? null) === 'forgotten';
    }

    /**
     * @return array{on: bool, folder: bool, name: ?string}
     */
    public function status(): array
    {
        if (! $this->enabled()) {
            return ['on' => false, 'folder' => false, 'name' => null];
        }

        $answer = Bridge::call('Calls.FolderStatus') ?? [];
        $folder = ($answer['folder'] ?? false) === true;
        $name = $folder && is_string($answer['name'] ?? null) ? mb_substr(trim($answer['name']), 0, 120) : null;

        return ['on' => ($answer['on'] ?? false) === true, 'folder' => $folder, 'name' => $name];
    }

    /**
     * The recording of one saved work call, as an app-private copy.
     *
     * @param  string  $callId  the call id the phone gave the call (Calls.Recent's `call_id`)
     * @param  string|null  $key  the call's compare key (a file named with another number is never taken)
     */
    public function recording(string $callId, int $startedAtMs, int $durationSeconds, ?string $key = null, int $maxMinutes = 60, int $maxBytes = 40 * 1024 * 1024): CallRecordingFile
    {
        if (! $this->enabled()) {
            return new CallRecordingFile('not_set_up');
        }

        if (preg_match('/^[A-Za-z0-9_:-]{1,80}$/', $callId) !== 1 || $startedAtMs <= 0) {
            return new CallRecordingFile('invalid');
        }

        $answer = Bridge::call('Calls.Recording', array_filter([
            'call_id' => $callId,
            'started_at_ms' => $startedAtMs,
            'duration_seconds' => max(0, $durationSeconds),
            'key' => $key !== null && preg_match('/^\d{6,15}$/', $key) === 1 ? $key : null,
            'max_minutes' => max(1, min(240, $maxMinutes)),
            'max_bytes' => max(1024, $maxBytes),
        ], fn ($value) => $value !== null));

        return CallRecordingFile::fromBridge($answer, self::EXTENSIONS);
    }

    /**
     * Removes the app's copy of a call's recording (sent, refused, or the call undone).
     */
    public function drop(string $callId): bool
    {
        if (! $this->enabled() || preg_match('/^[A-Za-z0-9_:-]{1,80}$/', $callId) !== 1) {
            return false;
        }

        return (Bridge::call('Calls.RecordingDrop', ['call_id' => $callId])['dropped'] ?? false) === true;
    }
}
