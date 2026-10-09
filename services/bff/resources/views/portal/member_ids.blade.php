@extends('portal.layout')
@section('title', 'TPA member ids')
@section('body')
<h1>Member ids and e-cards</h1>
<p class="sub">@if($gw === 'tpa')People added to policies who are waiting for a member id and e-card from you. Upload a spreadsheet and they have their cards within seconds.
@else When a TPA's server is down, or it sends the ids by email, upload its sheet here. Cover is already running, so the sooner the card is issued the better.@endif</p>
@if(! $board)<div class="card"><p class="empty">The list could not be loaded just now. Try again in a moment.</p></div>
@else
<div class="grid-2" style="margin-bottom:1rem">
  <div class="card"><div class="mut">Waiting for a member id</div><div style="font-size:2rem;font-weight:700">{{ number_format($board['total_pending_ids']) }}</div></div>
  <div class="card"><div class="mut">Longest wait</div><div style="font-size:2rem;font-weight:700">{{ $board['oldest_hours'] >= 48 ? round($board['oldest_hours'] / 24, 1).' days' : round($board['oldest_hours'], 1).' hours' }}</div></div>
</div>
<div class="card flush"><h2>Policies, longest wait first</h2><div class="table-wrap"><table><thead><tr><th>Policy</th><th>Waiting for id</th><th>Waiting for e-card</th><th>Waiting</th><th></th></tr></thead><tbody>
  @forelse($board['policies'] as $p)
  <tr><td><strong>{{ $p['policy_number'] ?? ('Policy '.$p['policy_id']) }}</strong></td><td>{{ $p['pending_ids'] }}</td><td>{{ $p['pending_ecards'] }}</td>
    <td><span class="pill {{ ['CRITICAL' => 'err', 'HIGH' => 'warn'][$p['urgency']] ?? '' }}">{{ $p['oldest_hours'] >= 48 ? round($p['oldest_hours'] / 24, 1).' days' : round($p['oldest_hours'], 1).' h' }}</span></td>
    <td class="right"><a class="btn sm" href="/app/member-ids/{{ $p['policy_id'] }}">Open</a></td></tr>
  @empty<tr><td colspan="5" class="empty">Nobody is waiting. Every member has an id.</td></tr>@endforelse</tbody></table></div></div>
@endif
@if(count($uploads))
<div class="card flush"><h2>Recent uploads</h2><div class="table-wrap"><table><thead><tr><th>When</th><th>Policy</th><th>File</th><th>By</th><th>Saved</th><th>Not saved</th></tr></thead><tbody>
  @foreach($uploads as $u)<tr><td class="nowrap">{{ \Illuminate\Support\Str::of($u['created_at'])->limit(16, '')->replace('T', ' ') }}</td><td>{{ $u['policy_id'] }}</td>
    <td><a href="/app/member-ids/uploads/{{ $u['id'] }}">{{ $u['filename'] }}</a></td><td>{{ $u['source'] === 'TPA_PORTAL' ? 'The TPA' : 'Broker, for the TPA' }}</td>
    <td>{{ $u['assigned'] + $u['reassigned'] + $u['ecards_added'] }}</td><td>{{ $u['rejected'] }}</td></tr>@endforeach</tbody></table></div></div>
@endif
@endsection
