{{-- Counted lines (the base's Screens\Lines): $lines, an optional $title. Rows, never a native list (it sits in the scroll view). --}}
@php($look = \Rishadblack\SyllooAppNative\Faces\NativeLook::class)

<native:column class="w-full gap-1">
    @if (filled($title ?? null))
        <native:text class="text-sm font-bold text-theme-on-surface-variant">{{ $title }}</native:text>
    @endif
    @foreach ($lines as $line)
        <native:row class="w-full items-center gap-3 p-3 rounded-lg bg-theme-surface">
            @if ($lineIcon = $look::icon($line['icon'] ?? null))
                <native:icon :name="$lineIcon" :size="20" class="{{ $look::text($line['variant'] ?? null) }}" />
            @endif
            <native:column class="flex-1">
                <native:text class="text-theme-on-surface">{{ $line['text'] }}</native:text>
                @if (filled($line['hint'] ?? null))
                    <native:text class="text-sm text-theme-on-surface-variant">{{ $line['hint'] }}</native:text>
                @endif
            </native:column>
            @if (($line['count'] ?? null) !== null)
                <native:text class="px-2 py-1 rounded-full text-sm font-bold {{ $look::soft($line['variant'] ?? null) }} {{ $look::text($line['variant'] ?? null) }}">{{ $line['count'] }}</native:text>
            @endif
        </native:row>
    @endforeach
</native:column>
