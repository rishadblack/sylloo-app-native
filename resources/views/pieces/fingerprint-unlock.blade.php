{{-- The fingerprint shortcut on the lock screen (plan B3), a piece at `lock.below_pad` (Faces\Fingerprint). --}}
<native:button class="w-full" variant="secondary" icon="fingerprint" @press="piece('fingerprint_unlock')">{{ __('sylloo-app-native::pieces.fingerprint_unlock') }}</native:button>
