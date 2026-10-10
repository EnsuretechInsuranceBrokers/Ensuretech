<?php

namespace Tests\Unit;

use App\Bff\Binder;
use PHPUnit\Framework\TestCase;

class BinderTest extends TestCase
{
    private function binder(): Binder
    {
        return new Binder([
            'item' => ['code' => ['to' => 'itemCode', 'type' => 'string', 'required' => true, 'max' => 5]],
            'main' => [
                'name' => ['to' => 'fullName', 'type' => 'string', 'required' => true, 'max' => 10, 'min' => 2],
                'kind' => ['type' => 'string', 'enum' => ['A', 'B']],
                'email' => ['type' => 'string', 'pattern' => '/^[^@]+@[^@]+$/'],
                'age' => ['type' => 'int', 'min' => 0, 'max' => 130],
                'rate' => ['type' => 'number', 'min' => 0, 'max' => 100],
                'on' => ['type' => 'bool'],
                'day' => ['type' => 'date'],
                'at' => ['type' => 'datetime'],
                'note' => ['type' => 'string', 'nullable' => true],
                'ids' => ['type' => 'list', 'of' => 'int', 'max' => 3],
                'tags' => ['type' => 'list', 'of' => 'string', 'item_max' => 3],
                'items' => ['type' => 'list', 'of' => 'input:item', 'max' => 2],
                'one' => ['type' => 'object', 'input' => 'item'],
                'cfg' => ['type' => 'map'],
            ],
        ]);
    }

    private function ok(array $in): array
    {
        [$out, $errors] = $this->binder()->bind($in, 'main');
        $this->assertSame([], $errors, json_encode($errors));

        return $out;
    }

    private function errors(array $in): array
    {
        [, $errors] = $this->binder()->bind($in, 'main');

        return $errors;
    }

    public function test_it_renames_drops_unknown_fields_and_keeps_good_values(): void
    {
        $out = $this->ok(['name' => 'Asha', 'kind' => 'A', 'age' => '42', 'rate' => '12.5', 'on' => true, 'day' => '2030-02-28', 'junk' => 'x', 'broker_id' => 9]);
        $this->assertSame(['fullName' => 'Asha', 'kind' => 'A', 'age' => 42, 'rate' => 12.5, 'on' => true, 'day' => '2030-02-28'], $out);
    }

    public function test_required_types_enums_patterns_and_limits_are_enforced(): void
    {
        $this->assertArrayHasKey('name', $this->errors([]));
        $this->assertArrayHasKey('name', $this->errors(['name' => 'x']));                       // min
        $this->assertArrayHasKey('name', $this->errors(['name' => str_repeat('x', 11)]));       // max
        $this->assertArrayHasKey('name', $this->errors(['name' => ['a']]));                     // type
        $this->assertArrayHasKey('name', $this->errors(['name' => "ab\x00"]));                  // control characters
        $e = $this->errors(['name' => 'Asha', 'kind' => 'C', 'email' => 'nope', 'age' => 131, 'rate' => 101, 'on' => 'yes', 'day' => '2030-02-30', 'at' => '2030-01-01', 'age2' => 1]);
        foreach (['kind', 'email', 'age', 'rate', 'on', 'day', 'at'] as $f) {
            $this->assertArrayHasKey($f, $e, $f);
        }
        $this->assertArrayHasKey('age', $this->errors(['name' => 'Asha', 'age' => '1.5']));
        $this->assertArrayHasKey('age', $this->errors(['name' => 'Asha', 'age' => 1.5]));
        $this->assertArrayHasKey('rate', $this->errors(['name' => 'Asha', 'rate' => 'NaN']));
        $this->assertArrayHasKey('rate', $this->errors(['name' => 'Asha', 'rate' => 'abc']));
    }

    public function test_dates_must_be_real_dates(): void
    {
        foreach (['2030-13-01', '2030-02-30', '30-01-2030', '2030-1-1', ' 2030-01-01'] as $bad) {
            $this->assertArrayHasKey('day', $this->errors(['name' => 'Asha', 'day' => $bad]), $bad);
        }
        $this->assertSame('2032-02-29', $this->ok(['name' => 'Asha', 'day' => '2032-02-29'])['day']);
        $this->assertSame('2030-01-01T10:00:00Z', $this->ok(['name' => 'Asha', 'at' => '2030-01-01T10:00:00Z'])['at']);
    }

    public function test_null_is_allowed_only_where_declared(): void
    {
        $this->assertSame(['fullName' => 'Asha', 'note' => null], $this->ok(['name' => 'Asha', 'note' => null]));
        $this->assertSame(['fullName' => 'Asha'], $this->ok(['name' => 'Asha', 'age' => null]));       // null on a non-nullable optional field = absent
        $this->assertArrayHasKey('name', $this->errors(['name' => null]));
    }

    public function test_lists_nested_objects_and_their_errors_are_located(): void
    {
        $out = $this->ok(['name' => 'Asha', 'ids' => ['1', 2], 'tags' => ['ab'], 'items' => [['code' => 'x', 'extra' => 1]], 'one' => ['code' => 'y']]);
        $this->assertSame([1, 2], $out['ids']);
        $this->assertSame([['itemCode' => 'x']], $out['items']);
        $this->assertSame(['itemCode' => 'y'], $out['one']);
        $e = $this->errors(['name' => 'Asha', 'ids' => [1, 2, 3, 4], 'tags' => ['abcd'], 'items' => [['code' => 'toolong'], ['x' => 1], 'str'], 'one' => 'nope']);
        $this->assertArrayHasKey('ids', $e);
        $this->assertArrayHasKey('tags.0', $e);
        $this->assertArrayHasKey('items', $e);                           // too many items
        $this->assertArrayHasKey('one', $e);
        $this->assertArrayHasKey('items.0.code', $this->errors(['name' => 'Asha', 'items' => [['code' => 'toolong']]]));
        $this->assertArrayHasKey('items.0.code', $this->errors(['name' => 'Asha', 'items' => [['x' => 1]]]));       // required inside an item
        $this->assertArrayHasKey('ids', $this->errors(['name' => 'Asha', 'ids' => ['a' => 1]]));                    // an object where a list is expected
    }

    public function test_free_form_maps_are_bounded(): void
    {
        $this->assertSame(['a' => ['b' => [1, 2]]], $this->ok(['name' => 'Asha', 'cfg' => ['a' => ['b' => [1, 2]]]])['cfg']);
        $deep = [];
        $cursor = &$deep;
        for ($i = 0; $i < 12; $i++) {
            $cursor['k'] = [];
            $cursor = &$cursor['k'];
        }
        $this->assertArrayHasKey('cfg', $this->errors(['name' => 'Asha', 'cfg' => $deep]));
        $this->assertArrayHasKey('cfg', $this->errors(['name' => 'Asha', 'cfg' => array_fill(0, 20001, 1)]));
        $this->assertArrayHasKey('cfg', $this->errors(['name' => 'Asha', 'cfg' => ['a' => str_repeat('x', 501)]]));
        $this->assertArrayHasKey('cfg', $this->errors(['name' => 'Asha', 'cfg' => [str_repeat('k', 81) => 1]]));
        $this->assertArrayHasKey('cfg', $this->errors(['name' => 'Asha', 'cfg' => 'text']));
    }

    public function test_a_body_that_is_a_list_is_not_an_object(): void
    {
        $this->assertNotEmpty($this->errors([1, 2]));
    }

    public function test_free_form_maps_can_be_recased_below_the_top_level(): void
    {
        $this->assertSame(['SPOUSE' => ['allowed' => true, 'windowDays' => 30, 'startBasis' => 'EVENT']],
            \App\Bff\Keys::camel(['SPOUSE' => ['allowed' => true, 'window_days' => 30, 'start_basis' => 'EVENT']]));
        $this->assertSame(['SPOUSE' => ['window_days' => 30]], \App\Bff\Keys::snake(['SPOUSE' => ['windowDays' => 30]]));
    }
}
