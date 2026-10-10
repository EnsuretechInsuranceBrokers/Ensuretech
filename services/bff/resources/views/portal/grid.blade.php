@extends('portal.layout')
@section('title', 'Enter employees')
@section('body')
@php $g = $plan['grid']; @endphp
<h1>Enter {{ $plan['employee_count'] }} employee{{ $plan['employee_count'] === 1 ? '' : 's' }}</h1>
<p class="sub">Type or paste from Excel. Every row is checked against the policy as you go; nothing is saved until you submit.</p>
<div class="gridpage" id="grid-app" data-id="{{ $id }}" data-op="{{ $plan['op_type'] }}" data-count="{{ $plan['employee_count'] }}" data-cap="{{ $g['row_cap'] }}" data-initial="{{ $g['initial_rows'] }}" data-eff="{{ $eff }}" data-token="{{ csrf_token() }}">
  <script type="application/json" id="grid-schema">@json($plan['schema'])</script>
  <div class="gridcol">
    <div class="toolbar"><button type="button" class="alt sm" id="add-row">+ Row</button><button type="button" class="alt sm" id="add-five">+ 5 rows</button><button type="button" class="alt sm" id="clear-blank">Remove empty rows</button><span class="hint" id="saving"></span></div>
    <div class="gridwrap"><table class="grid" id="grid"><thead></thead><tbody></tbody></table></div>
    <div class="submitbar"><span id="verdict" aria-live="polite"></span><button type="button" id="submit" disabled>Submit for processing</button></div>
  </div>
  <aside class="gridside" aria-live="polite" id="side"></aside>
</div>
@push('scripts')
<script nonce="{{ $nonce }}" src="/portal-grid.js"></script>
@endpush
@endsection
