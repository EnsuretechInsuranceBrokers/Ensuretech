@extends('portal.layout')
@section('title', 'Member ids')
@section('body')
@php
  $fmt = fn ($h) => $h >= 48 ? round($h / 24, 1).' days' : round($h, 1).' h';
  $tone = ['CRITICAL' => 'err', 'HIGH' => 'warn'];
@endphp
<p><a href="/app/member-ids">← All policies</a></p>
<h1>{{ $d['policy_number'] ?? ('Policy '.$d['policy_id']) }}</h1>
<p class="sub">Cover {{ $d['policy_start'] }} → {{ $d['policy_end'] }}. {{ $d['pending_ids'] }} waiting for a member id{{ $d["pending_ids"] ? ", the longest for ".$fmt($d["oldest_hours"]) : "" }}.</p>

@if($report)
<div class="card">
  <h2>{{ $report['applied'] ? 'Saved' : 'Checked, nothing saved yet' }}</h2>
  <p>@if($report['applied'])<strong>{{ $report['assigned'] + $report['reassigned'] }}</strong> member ids and <strong>{{ $report['ecards_added'] }}</strong> e-cards saved @if($report['longest_wait_hours']) (the longest had waited {{ $fmt($report['longest_wait_hours']) }})@endif.
     @else <strong>{{ $report['assigned'] + $report['reassigned'] }}</strong> member ids and <strong>{{ $report['ecards_added'] }}</strong> e-cards are ready to save.@endif
     @if($report['unchanged']) {{ $report['unchanged'] }} were already saved. @endif
     @if($report['rejected'])<strong>{{ $report['rejected'] }}</strong> lines cannot be used and are listed below; the others are not held up by them.@endif</p>
  @foreach($report['warnings'] as $w)<p class="hint">{{ $w }}</p>@endforeach
  @if(! $report['applied'] && $token && ($report['assigned'] + $report['reassigned'] + $report['updated']) > 0)
  <form method="post" action="/app/member-ids/{{ $d['policy_id'] }}/apply">@csrf<input type="hidden" name="token" value="{{ $token }}">
    <div class="actions"><button>Save {{ $report['assigned'] + $report['reassigned'] + $report['updated'] }} line(s)</button> <a class="btn alt" href="/app/member-ids/{{ $d['policy_id'] }}">Cancel</a></div></form>
  @endif
</div>
@if(count($report['lines']))
<div class="card flush"><h2>Line by line</h2><div class="table-wrap"><table><thead><tr><th>Line</th><th>Employee</th><th>Relation</th><th>TPA member id</th><th>Result</th></tr></thead><tbody>
  @foreach($report['lines'] as $l)<tr><td>{{ $l['line'] }}</td><td>{{ $l['employee_code'] }}</td><td>{{ $l['relation'] }}</td><td>{{ $l['tpa_member_id'] }}</td>
    <td>@if($l['outcome'] === 'REJECTED')<span class="pill err">Not saved</span> {{ $l['reason'] }}
        @elseif($l['outcome'] === 'UNCHANGED')<span class="pill">Already saved</span>
        @elseif($l['outcome'] === 'REASSIGNED')<span class="pill warn">Replaces {{ $l['previous_tpa_member_id'] }}</span>
        @else<span class="pill ok">{{ $report['applied'] ? 'Saved' : 'Ready' }}</span>@endif</td></tr>@endforeach</tbody></table></div></div>
@endif
@endif

@if($canUpload)
<div class="steps">
  <div class="card step"><span class="n">1</span><h2>Send the TPA this sheet</h2><p class="mut">It lists everyone still waiting, with the details the TPA needs to find them. The TPA fills in the yellow columns. Or skip this and upload the sheet the TPA already sent: the columns are recognised by name.</p>
    <a class="btn" href="/app/member-ids/{{ $d['policy_id'] }}/template">Download .xlsx</a></div>
  <div class="card step"><span class="n">2</span><h2>Upload the filled sheet</h2>
    <form class="form" method="post" action="/app/member-ids/{{ $d['policy_id'] }}/check" enctype="multipart/form-data">@csrf
      <label>Sheet from the TPA<input type="file" name="file" accept=".xlsx,.xls,.csv,text/csv" required></label>
      <label class="check"><input type="checkbox" name="ignore_cover_date" value="1"> The TPA's cover start dates are not reliable: do not check them</label>
      <p class="hint">Each line is checked against the people on this policy: the employee, the relationship, the cover start date and the endorsement reference. You see every result before anything is saved.</p>
      <div class="actions"><button>Check the sheet</button></div></form></div>
</div>
@else<p class="hint">You can see this list but not upload for the TPA.</p>@endif

<div class="card flush"><h2>Still waiting ({{ count($d['members']) }}@if($d['next_after'])+@endif)</h2><div class="table-wrap"><table><thead><tr><th>Employee</th><th>Relation</th><th>Name</th><th>Cover from</th><th>Member id</th><th>E-card</th><th>Waiting</th></tr></thead><tbody>
  @forelse($d['members'] as $m)<tr><td>{{ $m['employee_code'] }}</td><td>{{ $m['relation'] }}</td><td>{{ $m['name'] }}</td><td class="nowrap">{{ $m['cover_start'] }}</td>
    <td>{{ $m['tpa_member_id'] ?? '—' }}</td><td>@if($m['ecard_url'])<a href="{{ $m['ecard_url'] }}" rel="noopener noreferrer" target="_blank">Open</a>@else — @endif</td>
    <td><span class="pill {{ $tone[$m['urgency']] ?? '' }}">{{ $fmt($m['hours_waiting']) }}</span></td></tr>
  @empty<tr><td colspan="7" class="empty">Nobody is waiting on this policy.</td></tr>@endforelse</tbody></table></div></div>

@if(count($uploads))
<div class="card flush"><h2>Earlier uploads</h2><div class="table-wrap"><table><thead><tr><th>When</th><th>File</th><th>By</th><th>Saved</th><th>Not saved</th></tr></thead><tbody>
  @foreach($uploads as $u)<tr><td class="nowrap">{{ \Illuminate\Support\Str::of($u['created_at'])->limit(16, '')->replace('T', ' ') }}</td><td><a href="/app/member-ids/uploads/{{ $u['id'] }}">{{ $u['filename'] }}</a></td>
    <td>{{ $u['source'] === 'TPA_PORTAL' ? 'The TPA' : 'Broker, for the TPA' }}</td><td>{{ $u['assigned'] + $u['reassigned'] + $u['ecards_added'] }}</td><td>{{ $u['rejected'] }}</td></tr>@endforeach</tbody></table></div></div>
@endif
@endsection
