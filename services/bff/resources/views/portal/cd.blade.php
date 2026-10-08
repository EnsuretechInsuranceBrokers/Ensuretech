@extends('portal.layout')
@section('title', 'CD balance')
@section('body')
<h1>CD balance</h1>
<div class="card"><table><tr><th>Policy</th><th>Balance</th><th>Reserved</th><th>Available</th><th>Threshold</th><th></th></tr>
@forelse($accounts as $a)<tr><td>{{ $a['policy_id'] }}</td><td>{{ $a['balance'] }}</td><td>{{ $a['reserved'] }}</td><td>{{ $a['available'] }}</td><td>{{ $a['threshold_amount'] }} @if($a['below_threshold'] ?? false)<span class="tag">low</span>@endif</td>
<td>@if($gw === 'broker')
<form class="row" method="post" action="/app/cd/{{ $a['policy_id'] }}/credit">@csrf<input name="amount" type="number" step="0.01" min="0.01" placeholder="Amount" required style="width:7rem"><input name="reference" placeholder="Reference" required style="width:8rem"><select name="type"><option>DEPOSIT</option><option>ADJUSTMENT</option></select><button>Record</button></form>
@else
<form class="row" method="post" action="/app/cd/{{ $a['policy_id'] }}/replenish">@csrf<input name="amount" type="number" step="0.01" min="0.01" placeholder="Amount" required style="width:7rem"><input name="reference" placeholder="Reference" required style="width:8rem"><button>Request top-up</button></form>
@endif</td></tr>
@empty<tr><td colspan="6" class="mut">No accounts.</td></tr>@endforelse</table></div>
@endsection
