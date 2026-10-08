@extends('portal.layout')
@section('title', 'Endorsement')
@section('body')
<h1>{{ $job['source_filename'] }} <span class="tag" id="status">{{ $job['status'] }}</span></h1>
<div class="card"><div class="bar"><i id="bar"></i></div><p class="mut" id="phase">{{ $job['phase'] }} · {{ $job['category'] }}</p>
<table><tr><th>Operation</th><td>{{ $job['op_type'] }}</td><th>Effective</th><td>{{ $job['effective_date'] }}</td></tr>
<tr><th>Rows</th><td>{{ $job['total_rows'] }} ({{ $job['failed_rows'] }} failed)</td><th>CD</th><td>{{ $job['cd_status'] }}</td></tr>
<tr><th>Premium</th><td>{{ $job['total_premium'] }} (tax {{ $job['tax_amount'] }})</td><th>Delta</th><td>{{ $job['premium_delta'] }}</td></tr></table>
<form class="row" style="margin-top:.8rem">
  @foreach(['proceed' => 'Proceed', 'retry' => 'Retry', 'cancel' => 'Cancel'] as $a => $label)
  <button class="{{ $a === 'proceed' ? '' : 'alt' }}" formmethod="post" formaction="/app/endorsements/{{ $job['id'] }}/{{ $a }}">{{ $label }}</button>
  @endforeach @csrf
</form></div>
<div class="card"><h2>Problems found</h2>
<table><tr><th>Line</th><th>Employee</th><th>Field</th><th>Rule</th><th>Message</th></tr>
@forelse($rowErrors as $e)<tr><td>{{ $e['line'] }}</td><td>{{ $e['employee_code'] }}</td><td>{{ $e['field'] }}</td><td>{{ $e['rule'] }}</td><td>{{ $e['message'] }}</td></tr>
@empty<tr><td colspan="5" class="mut">None.</td></tr>@endforelse</table></div>
<div class="card"><h2>History</h2>
<table><tr><th>When</th><th>From</th><th>To</th><th>Actor</th></tr>
@foreach($audit as $a)<tr><td>{{ $a['at'] }}</td><td>{{ $a['from_status'] }}</td><td>{{ $a['to_status'] }}</td><td>{{ $a['actor'] }}</td></tr>@endforeach</table></div>
@unless($job['is_terminal'])
@push('scripts')
<script nonce="{{ $nonce }}">
const es = new EventSource('/app/endorsements/{{ $job['id'] }}/progress');
es.addEventListener('progress', e => { const d = JSON.parse(e.data);
  document.getElementById('status').textContent = d.status; document.getElementById('bar').style.width = (d.percent||0)+'%';
  document.getElementById('phase').textContent = d.phase + ' · ' + d.category; });
es.addEventListener('rest', () => es.close());
es.addEventListener('done', () => { es.close(); location.reload(); });
es.onerror = () => es.close();
</script>
@endpush
@endunless
@endsection
