@extends('portal.layout')
@section('title', 'Upload')
@section('body')
@php $u = $d['upload']; @endphp
<p><a href="/app/member-ids/{{ $u['policy_id'] }}">← Policy {{ $u['policy_id'] }}</a></p>
<h1>{{ $u['filename'] }}</h1>
<p class="sub">{{ $u['source'] === 'TPA_PORTAL' ? 'Uploaded by the TPA' : 'Uploaded by the broker for the TPA' }} · {{ \Illuminate\Support\Str::of($u['created_at'])->limit(16, '')->replace('T', ' ') }} UTC ·
  {{ $u['rows_total'] }} lines: {{ $u['assigned'] }} new ids, {{ $u['reassigned'] }} replaced, {{ $u['ecards_added'] }} e-cards, {{ $u['unchanged'] }} already saved, {{ $u['rejected'] }} not saved.</p>
<div class="card flush"><div class="table-wrap"><table><thead><tr><th>Line</th><th>Employee</th><th>Relation</th><th>TPA member id</th><th>Result</th></tr></thead><tbody>
  @foreach($d['lines'] as $l)<tr><td>{{ $l['line'] }}</td><td>{{ $l['employee_code'] }}</td><td>{{ $l['relation'] }}</td><td>{{ $l['tpa_member_id'] }}</td>
    <td>@if($l['outcome'] === 'REJECTED')<span class="pill err">Not saved</span> {{ $l['reason'] }}@else<span class="pill ok">{{ ucfirst(strtolower($l['outcome'])) }}</span>@endif</td></tr>@endforeach</tbody></table></div></div>
@endsection
