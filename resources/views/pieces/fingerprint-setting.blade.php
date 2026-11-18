{{-- Turn the fingerprint shortcut on or off (plan B3), a piece at `pin_settings.bottom` (Faces\Fingerprint). --}}
@if (\Rishadblack\SyllooAppNative\Faces\Fingerprint::isOn())
    <native:button class="w-full" variant="secondary" icon="fingerprint" @press="piece('fingerprint_off')">{{ __('sylloo-app-native::pieces.fingerprint_turn_off') }}</native:button>
@else
    <native:button class="w-full" variant="secondary" icon="fingerprint" @press="piece('fingerprint_on')">{{ __('sylloo-app-native::pieces.fingerprint_turn_on') }}</native:button>
@endif
