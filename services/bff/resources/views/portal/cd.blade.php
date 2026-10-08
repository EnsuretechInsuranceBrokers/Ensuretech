@extends('portal.layout')
@section('title', 'CD balance')
@section('body')
<h1>CD balance</h1><p class="sub">Client deposit held against each policy. Endorsements draw from it.</p>
<div class="card flush"><div class="table-wrap"><table><thead><tr><th>Policy</th><th class="right">Balance</th><th class="right">Reserved</th><th class="right">Available</th><th class="right">Threshold</th><th>{{ $gw === 'broker' ? 'Record money received' : 'Request a top-up' }}</th></tr></thead><tbody>
@forelse($accounts as $a)<tr>
  <td><a href="/app/policies/{{ $a['policy_id'] }}">#{{ $a['policy_id'] }}</a></td><td class="right num">{{ number_format($a['balance'], 2) }}</td><td class="right num">{{ number_format($a['reserved'], 2) }}</td>
  <td class="right num"><strong>{{ number_format($a['available'], 2) }}</strong></td><td class="right num">{{ number_format($a['threshold_amount'], 2) }} @if($a['below_threshold'] ?? false)<span class="pill warn">low</span>@endif</td>
  <td>@if($gw === 'broker')
    <form class="inline" method="post" action="/app/cd/{{ $a['policy_id'] }}/credit">@csrf<input name="amount" type="number" step="0.01" min="0.01" placeholder="Amount" required aria-label="Amount"><input name="reference" placeholder="Bank reference" required aria-label="Reference"><select name="type" aria-label="Type"><option>DEPOSIT</option><option>ADJUSTMENT</option></select><button class="sm">Record</button></form>
  @else
    <form class="inline" method="post" action="/app/cd/{{ $a['policy_id'] }}/replenish">@csrf<input name="amount" type="number" step="0.01" min="0.01" placeholder="Amount" required aria-label="Amount"><input name="reference" placeholder="Reference" required aria-label="Reference"><button class="sm">Request</button></form>
  @endif</td></tr>
@empty<tr><td colspan="6" class="empty">No deposit accounts yet. One opens when a policy goes live.</td></tr>@endforelse</tbody></table></div></div>
@endsection
