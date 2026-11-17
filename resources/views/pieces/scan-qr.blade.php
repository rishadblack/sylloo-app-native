{{-- The mobile app's "Scan QR code" on Activate (plan §7.2), a piece at `activate.below_code`: it runs the `scan_qr` piece action (Faces\ScanQr). --}}
<native:button class="w-full" variant="secondary" icon="qr_code_scanner" @press="piece('scan_qr')">{{ __('sylloo-app-native::pieces.scan_qr') }}</native:button>
