{{--
    One shared screen's native face, drawn from the base's ScreenView ($view), the open question ($question), the field
    errors ($fieldErrors), the hook context ($context), the app's counted lines by place ($placeLines) and the app's
    native pieces by place ($places). The native twin of the base's partials/screen-body: the same parts in the same
    order, with NativePHP's native components. Places ending in ".top" are drawn first, ".bottom" last, the others after
    the fields. Never a native list inside the scroll view (it can not be measured there): tiles and lines are rows.
--}}
@php
    use Rishadblack\SyllooAppBase\Support\Texts;
    use Rishadblack\SyllooAppNative\Faces\NativeLook;
    use Rishadblack\SyllooAppNative\Faces\NativeRoutes;

    $topPlaces = array_values(array_filter($view->places, fn ($place) => str_ends_with($place, '.top')));
    $bottomPlaces = array_values(array_filter($view->places, fn ($place) => str_ends_with($place, '.bottom')));
    $middlePlaces = array_values(array_diff($view->places, $topPlaces, $bottomPlaces));
    $steps = [1 => Texts::get('step_activate'), 2 => Texts::get('step_sign_in'), 3 => Texts::get('step_pin')];
    $modeLabel = match ($header['mode']) {
        'practice' => Texts::get('mode_practice'),
        'live' => Texts::get('mode_live'),
        default => null,
    };
@endphp

<native:top-bar :title="$header['title']" :subtitle="$modeLabel ?? ''">
    @if ($screen !== 'about' && NativeRoutes::path('about') !== null)
        <native:top-bar-action id="about" :label="Texts::get('about_title')" icon="info" @tap="openScreen('about')" />
    @endif
    @if ($screen !== 'language' && NativeRoutes::path('language') !== null)
        <native:top-bar-action id="language" :label="Texts::get('language_title')" icon="translate" @tap="openScreen('language')" />
    @endif
</native:top-bar>

<native:column class="w-full h-full bg-theme-background">
    @if ($header['practice'])
        <native:row class="w-full items-center gap-2 px-4 py-2 {{ NativeLook::soft('warning') }}">
            <native:icon name="construction" :size="18" class="{{ NativeLook::text('warning') }}" />
            <native:text class="flex-1 text-sm {{ NativeLook::text('warning') }}">{{ Texts::get('practice_bar') }}</native:text>
        </native:row>
    @endif

    <native:scroll-view class="w-full flex-1">
        <native:column class="w-full gap-4 p-5">
            @if ($view->step)
                <native:row class="w-full gap-2">
                    @foreach ($steps as $number => $name)
                        <native:row class="flex-1 items-center gap-1 px-2 py-1 rounded-full {{ $number === $view->step ? 'bg-theme-primary' : 'bg-theme-surface-variant' }}">
                            <native:text class="text-xs font-bold {{ $number === $view->step ? 'text-theme-on-primary' : 'text-theme-on-surface-variant' }}">{{ $number }}</native:text>
                            <native:text class="text-xs {{ $number === $view->step ? 'text-theme-on-primary' : 'text-theme-on-surface-variant' }}">{{ $name }}</native:text>
                        </native:row>
                    @endforeach
                </native:row>
            @endif

            @foreach ($topPlaces as $place)
                @include('sylloo-app-native::partials.place')
            @endforeach

            @if ($iconName = NativeLook::icon($view->icon))
                <native:icon :name="$iconName" :size="48" class="{{ NativeLook::text($view->variant) }}" />
            @endif

            <native:text class="text-2xl font-bold text-theme-on-background">{{ $view->title }}</native:text>

            @if (filled($view->text))
                <native:text class="text-base text-theme-on-surface-variant">{{ $view->text }}</native:text>
            @endif

            @if (filled($view->todo))
                <native:row class="w-full items-center gap-2">
                    <native:icon name="arrow_circle_right" :size="20" class="text-theme-primary" />
                    <native:text class="flex-1 text-theme-on-background">{{ $view->todo }}</native:text>
                </native:row>
            @endif

            @foreach ($view->notices as $notice)
                <native:row class="w-full items-center gap-2 p-3 rounded-lg {{ NativeLook::soft($notice['variant']) }}">
                    <native:icon name="{{ $notice['variant'] === 'danger' ? 'error' : 'info' }}" :size="20" class="{{ NativeLook::text($notice['variant']) }}" />
                    <native:text class="flex-1 {{ NativeLook::text($notice['variant']) }}">{{ $notice['text'] }}</native:text>
                </native:row>
            @endforeach

            @if ($view->card)
                <native:row class="w-full items-center gap-3 p-4 rounded-lg bg-theme-surface">
                    @if (filled($view->card['logo'] ?? null))
                        <native:image :src="$view->card['logo']" class="w-12 h-12 rounded-full" />
                    @else
                        <native:column class="w-12 h-12 rounded-full bg-theme-primary items-center justify-center">
                            <native:text class="text-lg font-bold text-theme-on-primary">{{ mb_strtoupper(mb_substr((string) ($view->card['business'] ?? '?'), 0, 1)) }}</native:text>
                        </native:column>
                    @endif
                    <native:column class="flex-1 gap-1">
                        <native:text class="text-lg font-bold text-theme-on-surface">{{ $view->card['business'] ?? '' }}</native:text>
                        <native:row class="gap-2">
                            <native:text class="px-2 py-1 rounded-full text-xs font-bold {{ NativeLook::soft(($view->card['mode'] ?? null) === 'practice' ? 'warning' : 'success') }} {{ NativeLook::text(($view->card['mode'] ?? null) === 'practice' ? 'warning' : 'success') }}">{{ $view->card['mode_label'] ?? '' }}</native:text>
                            @if (filled($view->card['kind_label'] ?? null))
                                <native:text class="px-2 py-1 rounded-full text-xs font-bold bg-theme-primary text-theme-on-primary">{{ $view->card['kind_label'] }}</native:text>
                            @endif
                        </native:row>
                        @if (filled($view->card['device_name'] ?? null))
                            <native:row class="items-center gap-1">
                                <native:icon name="smartphone" :size="16" class="text-theme-on-surface-variant" />
                                <native:text class="text-sm text-theme-on-surface-variant">{{ $view->card['device_name'] }}</native:text>
                            </native:row>
                        @endif
                    </native:column>
                </native:row>
            @endif

            @if ($view->lines !== [])
                @include('sylloo-app-native::partials.lines', ['lines' => \Rishadblack\SyllooAppBase\Screens\Lines::clean($view->lines), 'title' => $view->linesTitle])
            @endif

            @if ($view->rows !== [])
                <native:column class="w-full gap-2 p-4 rounded-lg bg-theme-surface">
                    @foreach ($view->rows as $label => $value)
                        @if (filled($value))
                            <native:row class="w-full gap-3">
                                <native:text class="w-1/3 text-sm text-theme-on-surface-variant">{{ $label }}</native:text>
                                <native:text class="flex-1 text-sm text-theme-on-surface">{{ $value }}</native:text>
                            </native:row>
                        @endif
                    @endforeach
                </native:column>
            @endif

            @if (! $question)
                @foreach ($view->fields as $field)
                    @include('sylloo-app-native::partials.field')
                @endforeach

                @foreach ($middlePlaces as $place)
                    @include('sylloo-app-native::partials.place')
                @endforeach

                @if ($view->buttons !== [])
                    <native:column class="w-full gap-2">
                        @foreach ($view->buttons as $button)
                            <native:button class="w-full" :variant="NativeLook::button($button->variant)" :icon="NativeLook::icon($button->icon) ?? ''" @press="act('{{ $button->action }}')">{{ $button->label }}</native:button>
                        @endforeach
                    </native:column>
                @endif
            @endif

            @foreach ($bottomPlaces as $place)
                @include('sylloo-app-native::partials.place')
            @endforeach
        </native:column>
    </native:scroll-view>
</native:column>

@if ($question)
    <native:modal :visible="true" @dismiss="act('answer_no')">
        <native:column class="w-full gap-3 p-6 rounded-lg bg-theme-surface">
            <native:text class="text-lg font-bold text-theme-on-surface">{{ $question['title'] }}</native:text>
            <native:text class="text-theme-on-surface">{{ $question['text'] }}</native:text>
            @if (filled($question['detail'] ?? null))
                <native:text class="font-bold text-theme-on-surface">{{ $question['detail'] }}</native:text>
            @endif
            @if (($question['lines'] ?? []) !== [])
                @include('sylloo-app-native::partials.lines', ['lines' => $question['lines'], 'title' => null])
            @endif
            <native:row class="w-full gap-2">
                <native:button class="flex-1" variant="ghost" @press="act('answer_no')">{{ $question['cancel'] }}</native:button>
                <native:button class="flex-1" :variant="($question['danger'] ?? false) ? 'destructive' : 'primary'" @press="act('answer_yes')">{{ $question['confirm'] }}</native:button>
            </native:row>
        </native:column>
    </native:modal>
@endif
