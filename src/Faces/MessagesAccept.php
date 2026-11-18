<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\MessagesAcceptScreen;

/**
 * The native face of phone SMS's accept screen ({@see MessagesAcceptScreen}); polls while Android's question is open.
 * Drawn only with `features.sms_send` on.
 */
class MessagesAccept extends ScreenFace
{
    use PollsTheScreen;

    protected function screenClass(): string
    {
        return MessagesAcceptScreen::class;
    }
}
