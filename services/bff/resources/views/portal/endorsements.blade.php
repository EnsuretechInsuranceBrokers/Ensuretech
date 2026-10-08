@extends('portal.layout')
@section('title', 'Endorsements')
@section('body')
<h1>Endorsements</h1>
<div class="card"><h2>Upload a file</h2>
<form class="row" method="post" action="/app/endorsements/upload" enctype="multipart/form-data">@csrf
  <label>Policy<select name="policy_id" required>@foreach($policies as $p)<option value="{{ $p['id'] }}">{{ $p['policy_number'] }}</option>@endforeach</select></label>
  <label>Operation<select name="op_type"><option>ADD</option><option>REMOVE</option><option>UPDATE</option></select></label>
  <label>Effective date<input type="date" name="effective_date"></label>
  <label>CSV file<input type="file" name="file" accept=".csv,text/csv" required></label>
  <button>Upload</button>
</form></div>
<div class="card"><table><tr><th>File</th><th>Op</th><th>Policy</th><th>Status</th><th>Rows</th><th>Failed</th><th>Premium delta</th><th></th></tr>
@forelse(($page['items'] ?? []) as $j)<tr><td>{{ $j['source_filename'] }}</td><td>{{ $j['op_type'] }}</td><td>{{ $j['policy_id'] }}</td><td><span class="tag">{{ $j['status'] }}</span></td><td>{{ $j['total_rows'] }}</td><td>{{ $j['failed_rows'] }}</td><td>{{ $j['premium_delta'] }}</td><td><a href="/app/endorsements/{{ $j['id'] }}">Open</a></td></tr>
@empty<tr><td colspan="8" class="mut">No endorsements yet.</td></tr>@endforelse</table></div>
@endsection
