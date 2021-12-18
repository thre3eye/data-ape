import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DaDataComponent } from './da-data.component';

describe('DaDataComponent', () => {
  let component: DaDataComponent;
  let fixture: ComponentFixture<DaDataComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ DaDataComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(DaDataComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
