@extends('portal.layout')
@section('title', 'Sign in')
@section('body')
<div class="auth"><div class="box">
  <div class="brand"><span class="logo">E</span> Ensuretech</div>
  <p class="sub">Sign in to manage policies, endorsements and deposits.</p>
  @if(session('err'))<div class="flash err" role="alert">{{ session('err') }}</div>@endif
  <form method="post" action="/login">@csrf
    <label>Email<input name="email" type="email" value="{{ old('email') }}" required autofocus autocomplete="username"></label>
    <label>Password<input name="password" type="password" required autocomplete="current-password"></label>
    <button>Sign in</button>
  </form>
</div></div>
@endsection
