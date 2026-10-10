@extends('portal.layout')
@section('title', $title)
@section('body')
<p class="mut" style="margin:0"><a href="/app/manage">Settings and tools</a> ›</p>
<h1>{{ $title }}</h1>
<p class="sub">{{ $blurb }}</p>
<div id="manage" data-token="{{ $token }}" data-cards="{{ json_encode($cards) }}" data-employers="{{ json_encode(array_map(fn ($e) => ['id' => $e['id'], 'name' => $e['display_name'] ?? $e['legal_name'] ?? ('Employer '.$e['id'])], $employers)) }}"
     data-policies="{{ json_encode(array_map(fn ($p) => ['id' => $p['id'], 'number' => $p['policy_number'] ?? ('Policy '.$p['id']), 'employer_id' => $p['employer_id'] ?? null, 'status' => $p['status'] ?? ''], $policies)) }}"></div>
@endsection
@push('scripts')
<script nonce="{{ $nonce }}" src="/portal-manage.js"></script>
@endpush
