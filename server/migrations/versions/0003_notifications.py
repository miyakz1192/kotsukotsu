"""notification settings and messages (phase 2.6)

Revision ID: 0003
Revises: 0002
"""
from alembic import op
import sqlalchemy as sa


revision = '0003'
down_revision = '0002'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('notification_settings',
    sa.Column('setting_id', sa.Integer(), nullable=False),
    sa.Column('enabled', sa.Boolean(), nullable=False),
    sa.Column('inactive_days', sa.Integer(), nullable=False),
    sa.Column('weekday_times', sa.JSON(), nullable=False),
    sa.Column('holiday_times', sa.JSON(), nullable=False),
    sa.Column('point_message_every', sa.Integer(), nullable=False),
    sa.Column('updated_at', sa.DateTime(), nullable=False),
    sa.Column('updated_by', sa.String(), nullable=True),
    sa.ForeignKeyConstraint(['updated_by'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('setting_id')
    )
    op.create_table('notification_messages',
    sa.Column('message_id', sa.String(), nullable=False),
    sa.Column('kind', sa.String(), nullable=False),
    sa.Column('text', sa.Text(), nullable=False),
    sa.Column('is_active', sa.Boolean(), nullable=False),
    sa.Column('sort', sa.Integer(), nullable=False),
    sa.Column('updated_at', sa.DateTime(), nullable=False),
    sa.PrimaryKeyConstraint('message_id')
    )


def downgrade() -> None:
    op.drop_table('notification_messages')
    op.drop_table('notification_settings')
