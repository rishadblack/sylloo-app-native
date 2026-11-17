{{-- What the app added at this hook place ($place): its native pieces (NativePieces), then its counted lines (the base's Hooks::lines()). --}}
@foreach ($places[$place] ?? [] as $piece)
    @include($piece, ['context' => $context, 'place' => $place])
@endforeach

@if (($placeLines[$place] ?? []) !== [])
    @include('sylloo-app-native::partials.lines', ['lines' => $placeLines[$place], 'title' => null])
@endif
