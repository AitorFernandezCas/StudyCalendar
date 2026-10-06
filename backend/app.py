import os
from studycalendar.factory import create_app

__all__ = ['app', 'create_app']

app = create_app()

if __name__ == '__main__':
    app.run(host='127.0.0.1', port=int(os.environ.get('PORT', '5000')), debug=os.environ.get('FLASK_ENV') == 'development')
