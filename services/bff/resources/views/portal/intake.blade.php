@extends('portal.layout')
@section('title', 'Add or remove people')
@section('body')
<h1>Add or remove people</h1><p class="sub">How many employees are you changing? Up to {{ config('bff.grid_max_employees') }} you type them into a grid that checks every row as you go; for more, we give you a spreadsheet made for your policy.</p>
<div class="card"><form class="form" method="post" action="/app/endorse/plan">@csrf
  @include('portal._employer_policy', ['chosen' => old('policy_id')])
  <label>What is changing<select name="op_type"><option value="ADD" @selected(old('op_type') === 'ADD')>Add people</option><option value="REMOVE" @selected(old('op_type') === 'REMOVE')>Remove people</option><option value="UPDATE" @selected(old('op_type') === 'UPDATE')>Correct details</option></select></label>
  <label>Number of employees<input type="number" name="employee_count" min="1" max="1000000" required value="{{ old('employee_count', 10) }}" id="count" data-limit="{{ config('bff.grid_max_employees') }}"></label>
  <label>Cover starts<input type="date" name="effective_date" value="{{ old('effective_date') }}"></label>
  <p class="hint" id="route" aria-live="polite"></p>
  <div class="actions"><button>Continue</button></div></form></div>
@push('scripts')
<script nonce="{{ $nonce }}" src="/portal-intake.js"></script>
@endpush
@endsection
@push('scripts')
<script nonce="{{ $nonce }}" src="/portal-pickers.js"></script>
@endpush
