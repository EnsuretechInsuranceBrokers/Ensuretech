@extends('portal.layout')
@section('title', 'Assistant')
@section('body')
<h1>Assistant</h1><p class="sub">Ask about policies and endorsement rules. Answers come from the broker's knowledge base.</p>
<div class="card"><form class="form" method="post" action="/app/assistant">@csrf
  <label class="wide">Your question<input name="question" value="{{ $question }}" maxlength="1000" required placeholder="How are mid-term joiners charged?"></label><div class="actions"><button>Ask</button></div></form></div>
@if($answer)<div class="card"><h2>Answer</h2><p style="white-space:pre-wrap;margin:0 0 .8rem">{{ $answer['answer'] }}</p>
  @foreach(($answer['citations'] ?? []) as $c)<div class="mut">[{{ $c['n'] }}] {{ $c['title'] }}</div>@endforeach</div>@endif
@endsection
