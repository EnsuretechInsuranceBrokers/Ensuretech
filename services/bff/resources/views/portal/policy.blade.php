@extends('portal.layout')
@section('title', 'Policy '.$p['policy_number'])
@section('body')
<h1>Policy {{ $p['policy_number'] }} <span class="tag">{{ $p['status'] }}</span></h1>
<div class="card"><table>
<tr><th>Type</th><td>{{ $p['policy_type'] }} {{ $p['policy_sub_type'] }}</td><th>Period</th><td>{{ $p['start_date'] }} → {{ $p['end_date'] }}</td></tr>
<tr><th>Max members</th><td>{{ $p['max_members'] ?? '–' }}</td><th>Pro-rata</th><td>{{ $p['pro_rata_type'] }}</td></tr></table></div>
@if($ready !== null && $p['status'] === 'NOT_APPROVED')
<div class="card"><h2>Readiness</h2>
@if($ready['valid'] ?? false)<p>Ready to approve.</p><form method="post" action="/app/policies/{{ $p['id'] }}/approve">@csrf<button>Approve and go live</button></form>
@else<ul>@foreach(($ready['issues'] ?? []) as $i)<li>{{ $i['message'] }}</li>@endforeach</ul>@endif</div>
@endif
@if(!empty($cd))<div class="card"><h2>CD account</h2><table><tr><th>Balance</th><th>Reserved</th><th>Available</th><th>Threshold</th></tr>
<tr><td>{{ $cd['balance'] ?? '–' }}</td><td>{{ $cd['reserved'] ?? '–' }}</td><td>{{ $cd['available'] ?? '–' }}</td><td>{{ $cd['threshold_amount'] ?? '–' }}</td></tr></table></div>@endif
@endsection
