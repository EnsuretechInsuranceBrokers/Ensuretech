@extends('portal.layout')
@section('title', 'Spreadsheet')
@section('body')
@php $f = $plan['file']; @endphp
<h1>{{ number_format($plan['employee_count']) }} employees: use the spreadsheet</h1>
<p class="sub">More than {{ $plan['grid_max_employees'] }} employees is quicker in Excel. The sheet below is made for this policy, so most mistakes cannot be typed into it.</p>
<div class="steps">
  <div class="card step"><span class="n">1</span><h2>Download your sheet</h2><p class="mut">{{ number_format($f['template_rows']) }} rows, one SELF row per employee, the start date filled in. Put each dependant on their own row under the employee, with the same employee code. Relation and gender are drop-downs; dates, amounts and PANs refuse the wrong kind of value.</p>
    <a class="btn" href="/app/endorse/template?{{ http_build_query($q) }}">Download .xlsx</a></div>
  <div class="card step"><span class="n">2</span><h2>Fill it in</h2><p class="mut">Paste from your HR system. The column names already match, so the upload needs no mapping step.</p></div>
  <div class="card step"><span class="n">3</span><h2>Upload it</h2>
    <form class="form" method="post" action="/app/endorsements/upload" enctype="multipart/form-data">@csrf
      <input type="hidden" name="policy_id" value="{{ $q['policy_id'] }}"><input type="hidden" name="op_type" value="{{ $q['op_type'] }}">@if(!empty($q['effective_date']))<input type="hidden" name="effective_date" value="{{ $q['effective_date'] }}">@endif
      <label>Filled sheet<input type="file" name="file" accept=".xlsx,.csv,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" required></label>
      <p class="hint">It is read in slices of {{ $f['chunk_rows'] }} rows (about {{ number_format($f['estimated_chunks']) }} for this size), and each slice is checked on its own, so several workers can check a big sheet at once. Rows with mistakes are listed by line number, and the good ones can still go ahead.</p>
      <div class="actions"><button>Upload and check</button></div></form></div>
</div>
<p><a href="/app/endorse">Change the number</a></p>
@endsection
