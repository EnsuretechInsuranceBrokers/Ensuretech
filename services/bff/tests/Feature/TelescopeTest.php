<?php

namespace Tests\Feature;

use Tests\TestCase;

class TelescopeTest extends TestCase
{
    public function test_telescope_does_not_exist_unless_it_is_local_and_switched_on(): void
    {
        $this->assertFalse(config('telescope.enabled'));
        $this->assertFalse($this->app->providerIsLoaded(\App\Providers\TelescopeServiceProvider::class));
        $this->get('/telescope')->assertNotFound();
        $this->get('/telescope/telescope-api/requests')->assertNotFound();
    }

    public function test_ray_is_off_unless_local_and_switched_on_and_sends_nothing_when_off(): void
    {
        $this->assertTrue(ray('x')->disabled());          // RAY_ENABLED is not set in the test environment, so nothing is ever sent
    }

    public function test_even_when_enabled_it_never_records_tokens_or_the_gateway_signature(): void
    {
        (new \ReflectionClass(\App\Providers\TelescopeServiceProvider::class))->getMethod('hideSensitiveDetails')->invoke(new \App\Providers\TelescopeServiceProvider($this->app));
        foreach (['authorization', 'cookie', 'x-gateway-signature'] as $h) {
            $this->assertContains($h, \Laravel\Telescope\Telescope::$hiddenRequestHeaders);
        }
        foreach (['password', 'refresh_token', 'client_secret'] as $f) {
            $this->assertContains($f, \Laravel\Telescope\Telescope::$hiddenRequestParameters);
        }
        $this->assertContains('access_token', \Laravel\Telescope\Telescope::$hiddenResponseParameters);
    }
}
