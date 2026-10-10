{{-- Employer dropdown that narrows the policy dropdown. Expects $employers (id => name), $policies, optional $chosen (policy id). Employer users see no employer dropdown: they have one employer. --}}
@if(count($employers) > 0)
<label>Employer<select id="employer-pick" data-narrows="policy-pick"><option value="">All employers</option>@foreach($employers as $id => $name)<option value="{{ $id }}">{{ $name }}</option>@endforeach</select></label>
@endif
<label>Policy<select id="policy-pick" name="policy_id" required>@foreach($policies as $p)<option value="{{ $p['id'] }}" data-employer="{{ $p['employer_id'] ?? '' }}" @selected(($chosen ?? null) == $p['id'])>{{ $p['policy_number'] }}@if(isset($employers[$p['employer_id'] ?? 0])) · {{ $employers[$p['employer_id']] }}@endif</option>@endforeach</select></label>
