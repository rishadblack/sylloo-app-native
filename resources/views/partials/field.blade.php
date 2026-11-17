{{--
    One field of the base's view ($field), drawn natively by its type: `code`, `text`, `pin`, `pin_pad`, `tiles`,
    `select` (the same types the web face draws). Bound to `fields.<name>`; its error is $fieldErrors[<name>].
--}}
@php
    $error = $fieldErrors[$field->name] ?? null;
    $supporting = $error ?? $field->hint ?? '';
@endphp

@switch ($field->type)
    @case('code')
        <native:outlined-text-input class="w-full" :label="$field->label ?? ''" keyboard="number" max-length="19"
            :error="$error !== null" :supporting="$supporting"
            native:model.debounce.300ms="fields.{{ $field->name }}" @submit="submit('{{ $field->name }}')" />
        @break

    @case('pin')
        <native:outlined-text-input class="w-full" :label="$field->label ?? ''" keyboard="number" secure revealable :max-length="$pad['max']"
            :error="$error !== null" :supporting="$supporting"
            native:model.debounce.300ms="fields.{{ $field->name }}" @submit="submit('{{ $field->name }}')" />
        @break

    @case('pin_pad')
        @php($typed = mb_strlen($fields[$field->name] ?? ''))
        <native:column class="w-full items-center gap-4">
            @if (filled($field->label))
                <native:text class="text-theme-on-surface-variant">{{ $field->label }}</native:text>
            @endif
            <native:row class="gap-3">
                @for ($slot = 1; $slot <= max($pad['min'], min($pad['max'], $typed + 1)); $slot++)
                    <native:column class="w-4 h-4 rounded-full {{ $typed >= $slot ? 'bg-theme-primary' : 'bg-theme-surface-variant' }}" />
                @endfor
            </native:row>
            @if ($error !== null)
                <native:text class="text-sm text-red-600">{{ $error }}</native:text>
            @endif
            @foreach ([['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9']] as $keys)
                <native:row class="w-full gap-3">
                    @foreach ($keys as $digit)
                        <native:button class="flex-1" variant="secondary" size="lg" @press="pad('{{ $field->name }}', '{{ $digit }}')">{{ $digit }}</native:button>
                    @endforeach
                </native:row>
            @endforeach
            <native:row class="w-full gap-3">
                <native:button class="flex-1" variant="ghost" size="lg" icon="backspace" :a11y-label="\Rishadblack\SyllooAppBase\Support\Texts::get('pin_erase')" @press="padErase('{{ $field->name }}')" />
                <native:button class="flex-1" variant="secondary" size="lg" @press="pad('{{ $field->name }}', '0')">0</native:button>
                <native:button class="flex-1" variant="primary" size="lg" icon="check" :disabled="$typed < $pad['min']" :a11y-label="\Rishadblack\SyllooAppBase\Support\Texts::get('pin_ok')" @press="padDone('{{ $field->name }}')" />
            </native:row>
        </native:column>
        @break

    @case('tiles')
        <native:column class="w-full gap-2">
            @if (filled($field->label))
                <native:text class="text-sm text-theme-on-surface-variant">{{ $field->label }}</native:text>
            @endif
            @foreach ($field->options as $key => $tile)
                <native:row class="w-full items-center gap-3 p-3 rounded-lg {{ ($tile['current'] ?? false) ? 'bg-theme-primary/15' : 'bg-theme-surface' }}" @press="act('{{ $field->action }}', '{{ $key }}')">
                    @if (filled($tile['photo'] ?? null))
                        <native:image :src="$tile['photo']" class="w-10 h-10 rounded-full" />
                    @else
                        <native:column class="w-10 h-10 rounded-full bg-theme-primary items-center justify-center">
                            <native:text class="font-bold text-theme-on-primary">{{ mb_strtoupper(mb_substr((string) ($tile['label'] ?? '?'), 0, 1)) }}</native:text>
                        </native:column>
                    @endif
                    <native:column class="flex-1">
                        <native:text class="text-theme-on-surface">{{ $tile['label'] ?? '' }}</native:text>
                        @if (filled($tile['hint'] ?? null))
                            <native:text class="text-sm text-theme-on-surface-variant">{{ $tile['hint'] }}</native:text>
                        @endif
                    </native:column>
                </native:row>
            @endforeach
        </native:column>
        @break

    @case('select')
        @php($labels = array_map(fn ($option) => is_scalar($option) ? (string) $option : '', $field->options))
        <native:select class="w-full" :label="$field->label ?? ''" :options="array_values($labels)" :value="$labels[$fields[$field->name] ?? ''] ?? ''"
            :supporting="$supporting" @change="choose('{{ $field->name }}')" />
        @break

    @default
        <native:outlined-text-input class="w-full" :label="$field->label ?? ''"
            :error="$error !== null" :supporting="$supporting"
            native:model.debounce.300ms="fields.{{ $field->name }}" @submit="submit('{{ $field->name }}')" />
@endswitch
