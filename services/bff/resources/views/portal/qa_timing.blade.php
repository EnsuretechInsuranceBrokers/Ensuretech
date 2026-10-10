@extends('portal.layout')
@section('title', 'QA timing')
@section('body')
<h1>QA timing</h1>
<p class="sub">Buttons that build a sheet, upload it through the same API the portal uses, and time every step until the result is ready: the job's final status and, when rows fail, the error spreadsheet. Times are measured in this browser from the click. For testing only (it exists only while <code>BFF_QA_TOOLS</code> is on).</p>
<div id="qa" data-token="{{ $token }}" class="card">
  <div class="form" style="display:flex;gap:.8rem;flex-wrap:wrap;align-items:end">
    <label>Policy<select id="policy">@foreach($policies as $p)<option value="{{ $p['id'] }}">{{ $p['policy_number'] ?? ('policy '.$p['id']) }} (#{{ $p['id'] }})</option>@endforeach</select></label>
    <label>Rows<input id="rows" type="number" min="1" max="50000" value="1000" style="width:6rem"></label>
    <label>Invalid rows (mixed)<input id="invalid" type="number" min="1" max="50000" value="4" style="width:6rem"></label>
    <label>Effective date<input id="eff" type="date" value="{{ $effective }}"></label>
  </div>
  <div style="display:flex;gap:.5rem;flex-wrap:wrap;margin-top:1rem">
    <button data-run="valid">Run: all rows valid</button>
    <button data-run="mixed">Run: some rows invalid</button>
    <button data-run="invalid">Run: all rows invalid</button>
    <button class="alt" data-run="all">Run all three, one after another</button>
  </div>
  <div style="display:flex;gap:.5rem;flex-wrap:wrap;margin-top:.8rem;align-items:end">
    <span class="mut">Get the same sheet to submit elsewhere:</span>
    <button class="alt" data-sheet="valid">Download valid sheet</button><button class="alt" data-sheet="mixed">Download mixed sheet</button><button class="alt" data-sheet="invalid">Download invalid sheet</button>
  </div>
  <div style="display:flex;gap:.5rem;flex-wrap:wrap;margin-top:.8rem;align-items:end">
    <label>Add to this policy's deposit (₹)<input id="amount" type="number" min="1" value="50000000" style="width:11rem"></label><button class="alt" id="topup">Add deposit</button>
    <span class="mut">A big sheet stops at the deposit check if the balance is too low.</span>
  </div>
  <p id="note" class="mut" role="status" style="margin:.8rem 0 0"></p>
</div>
<div class="card flush"><h2>Results</h2><div class="table-wrap"><table><thead><tr><th>Case</th><th>Rows</th><th>Upload accepted</th><th>Result</th><th>Until result</th><th>Error sheet</th><th>Until sheet ready</th><th></th></tr></thead>
<tbody id="results"><tr><td colspan="8" class="empty">Nothing run yet.</td></tr></tbody></table></div></div>
@endsection
@push('scripts')
<script nonce="{{ $nonce }}" src="/portal-qa.js"></script>
@endpush
