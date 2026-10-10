@php
  $s = (string) ($s ?? '');
  $c = match (true) {
    (bool) preg_match('/^(FAILED|.*_FAILED|CANCELLED.*|REJECTED|INACTIVE|DEAD.*)$/', $s) => 'err',
    (bool) preg_match('/^(ENDORSED|CONFIRMED|OPEN|APPROVED.*|SUFFICIENT|COMPLETED|DONE|PROCESSED|SYNCED|ACTIVE)$/', $s) => 'ok',
    (bool) preg_match('/^(CD_INSUFFICIENT|INSUFFICIENT|VALIDATION_PARTIAL|NOT_APPROVED|REQUESTED|CONFIRMED_BUT_NOT_OPTED|PARTIAL.*|PENDING.*)$/', $s) => 'warn',
    (bool) preg_match('/(IN_PROGRESS|QUEUED|PROCESSING|SENT_TO|AWAITING|RAISED)/', $s) => 'info',
    default => '',
  };
@endphp<span class="pill {{ $c }}">{{ str_replace('_', ' ', $s) }}</span>
