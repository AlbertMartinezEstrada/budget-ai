from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
import pandas as pd
import io
import os
import psycopg2
from datetime import datetime
from bank_reader import netejar_banc
from ai_engine import classificar_gastos_amb_ia

app = FastAPI()

# Configurar CORS para que el frontend pueda acceder
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Connexió a la BBDD (Docker fa servir el nom del servei 'db')
def get_db_connection():
    return psycopg2.connect(
        host=os.getenv("DB_HOST", "db"),
        database=os.getenv("DB_NAME", "budget_db"),
        user=os.getenv("DB_USER", "albert"),
        password=os.getenv("DB_PASSWORD", "1234567")
    )

@app.get("/")
def read_root():
    return {"status": "API Budget AI funcionant correctament", "version": "1.0"}

@app.post("/upload-csv")
async def upload_csv(file: UploadFile = File(...)):
    try:
        # 1. Guardar temporalmente el archivo
        temp_path = f"/tmp/{file.filename}"
        contents = await file.read()
        with open(temp_path, "wb") as f:
            f.write(contents)

        # 2. Usar bank_reader para limpiar los datos
        df_netejat = netejar_banc(temp_path)

        if df_netejat is None or len(df_netejat) == 0:
            raise HTTPException(status_code=400, detail="No s'han trobat gastos al CSV")

        # 3. Clasificar con IA
        df_classificat = classificar_gastos_amb_ia(df_netejat)

        if df_classificat is None or len(df_classificat) == 0:
            raise HTTPException(status_code=500, detail="Error en la classificació amb IA")

        # 4. Guardar a PostgreSQL
        conn = get_db_connection()
        cur = conn.cursor()

        count = 0
        for _, fila in df_classificat.iterrows():
            # Convertir la fecha del formato DD/MM/YYYY a YYYY-MM-DD para PostgreSQL
            fecha_str = fila.get('date', fila.get('data', None))

            # Convertir de DD/MM/YYYY a YYYY-MM-DD
            if fecha_str:
                try:
                    fecha_obj = datetime.strptime(fecha_str, '%d/%m/%Y')
                    fecha_iso = fecha_obj.strftime('%Y-%m-%d')
                except ValueError:
                    # Si ya está en formato ISO o es otro formato, intenta usarlo directamente
                    fecha_iso = fecha_str
            else:
                fecha_iso = None

            cur.execute(
                """INSERT INTO despeses (data, empresa, categoria, descripcio_curta, cost, concepte_original) 
                   VALUES (%s, %s, %s, %s, %s, %s)""",
                (
                    fecha_iso,
                    fila.get('companyName', 'Desconegut'),
                    fila.get('category', 'Altres'),
                    fila.get('description_curta', ''),
                    float(fila.get('cost', 0)),
                    fila.get('concepte_original', 'Càrrega CSV')
                )
            )
            count += 1

        conn.commit()
        cur.close()
        conn.close()

        # Limpiar archivo temporal
        if os.path.exists(temp_path):
            os.remove(temp_path)

        return {
            "status": "success",
            "message": f"{count} despeses guardades a la BBDD",
            "data": df_classificat.to_dict(orient="records")
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processant el fitxer: {str(e)}")

@app.get("/gastos")
def get_gastos():
    try:
        conn = get_db_connection()
        df = pd.read_sql("SELECT * FROM despeses ORDER BY data DESC", conn)
        conn.close()

        # Convertir fechas a string y manejar NaN
        if not df.empty:
            # Convertir la columna 'data' a string en formato ISO
            if 'data' in df.columns:
                df['data'] = pd.to_datetime(df['data']).dt.strftime('%Y-%m-%d')

            # Reemplazar NaN por None (null en JSON)
            df = df.fillna('')

            # Convertir a diccionario
            result = df.to_dict(orient="records")

            # Asegurar que los valores numéricos son válidos
            for record in result:
                if 'cost' in record:
                    try:
                        record['cost'] = float(record['cost']) if record['cost'] != '' else 0.0
                    except (ValueError, TypeError):
                        record['cost'] = 0.0

            return result
        else:
            return []
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error carregant gastos: {str(e)}")
