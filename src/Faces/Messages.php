<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\MessagesScreen;

/**
 * The native face of phone SMS's Messages screen ({@see MessagesScreen}); asks again every 15 s while open. Drawn only
 * with `features.sms_send` on.
 */
class Messages extends ScreenFace
{
    use PollsTheScreen;

    protected function screenClass(): string
    {
        return MessagesScreen::class;
    }
}
