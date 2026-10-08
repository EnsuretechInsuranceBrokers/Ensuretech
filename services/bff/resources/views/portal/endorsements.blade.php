@extends('portal.layout')
@section('title', 'Endorsements')
@section('body')
<h1>Endorsements</h1><p class="sub">Add, remove or update employees by uploading a CSV file.</p>
<div class="card"><h2>Upload a file</h2>
  <form class="form" method="post" action="/app/endorsements/upload" enctype="multipart/form-data">@csrf
    <label>Policy<select name="policy_id" required>@foreach($policies as $p)<option value="{{ $p['id'] }}">{{ $p['policy_number'] }}</option>@endforeach</select></label>
    <label>Operation<select name="op_type"><option>ADD</option><option>REMOVE</option><option>UPDATE</option></select></label>
    <label>Effective date<input type="date" name="effective_date"></label>
    <label>CSV file<input type="file" name="file" accept=".csv,text/csv" required></label>
    <div class="actions"><button>Upload</button></div></form></div>
<div class="card flush"><div class="table-wrap"><table><thead><tr><th>File</th><th>Op</th><th>Policy</th><th>Status</th><th class="right">Rows</th><th class="right">Failed</th><th class="right">Premium delta</th><th></th></tr></thead><tbody>
  @forelse(($page['items'] ?? []) as $j)<tr><td>{{ $j['source_filename'] }}</td><td>{{ $j['op_type'] }}</td><td>#{{ $j['policy_id'] }}</td><td>@include('portal.pill', ['s' => $j['status']])</td><td class="right num">{{ $j['total_rows'] }}</td><td class="right num">{{ $j['failed_rows'] }}</td><td class="right num">{{ $j['premium_delta'] }}</td><td class="right"><a href="/app/endorsements/{{ $j['id'] }}">Open</a></td></tr>
  @empty<tr><td colspan="8" class="empty">No endorsements yet.</td></tr>@endforelse</tbody></table></div></div>
@endsection
